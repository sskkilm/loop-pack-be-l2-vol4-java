# 상품 API Redis 캐시 설계

## 배경

상품 상세 API(`GET /api/v1/products/{id}`), 상품 목록 API(`GET /api/v1/products`)에 Redis 캐시를 적용한다.
둘 다 `ProductFacade`에서 브랜드/재고/`product_stats`(좋아요 수)를 조합해 `ProductInfo`를 만드는데, 이 조합 비용을 캐시로 줄인다.

`@Cacheable` 같은 Spring Cache 추상화는 쓰지 않고, `RedisTemplate`을 직접 다루는 캐시 컴포넌트를 둔다. 캐시 키 구조가 상세/목록에서 서로 다르고(단일 키 vs 조합 키), 목록은 무효화 대신 TTL만 적용하는 등 캐시 종류별로 동작이 달라 선언적 어노테이션보다 명시적 코드가 더 적합하다고 판단했다.

## 캐싱 대상

| API | 캐시 단위 | 무효화 |
|---|---|---|
| 상품 상세 (`ProductFacade.getProduct`) | productId 1건 | 즉시 무효화 + TTL |
| 상품 목록 (`ProductFacade.getProducts`) | brandId × sort × page × size 조합 | TTL만 (무효화 없음) |

두 캐시는 무효화 전략이 다르므로 별도 키 네임스페이스와 별도 TTL을 가진다.

## 캐시 키 설계

```
product:detail:{productId}
product:list:{brandId|"all"}:{sort}:{page}:{size}
```

- `brandId`가 없으면(`null`) 토큰 `all`을 사용한다.
- `sort`는 `SortType` enum 값(`LATEST` / `PRICE_ASC` / `LIKES_DESC`)을 그대로 사용한다.
- 목록 키는 브랜드(10개) × 정렬(3) × 페이지 조합으로 사실상 무한히 늘어난다. 이 조합 폭발이 목록 캐시를 "무효화 대상으로 찾아가서 지우는" 방식 대신 TTL 단독 운영으로 가는 이유다.

## 직렬화

`RedisConfig`의 `defaultRedisTemplate`(`@Primary`, `REPLICA_PREFERRED`)은 `RedisTemplate<String, String>`으로 키/값 모두 `StringRedisSerializer`로 고정되어 있다. 값은 JSON 문자열로 직접 직렬화해 저장한다.

- `ObjectMapper`는 새로 만들지 않고 `supports:jackson`의 `JacksonConfig`가 커스터마이즈한 Spring Boot 자동 구성 빈을 그대로 주입받는다.
- 상세 캐시 값: `ProductInfo`를 그대로 직렬화한다.
- 목록 캐시 값: `Page<ProductInfo>`(`PageImpl`)를 그대로 직렬화하지 않는다. Jackson은 `PageImpl`을 역직렬화할 기본 생성자/세터를 제공하지 않아 캐시에서 읽어올 때 원형 그대로 복원할 수 없다. 대신 캐시 전용 직렬화 모델을 둔다.

```java
record ProductListCacheValue(List<ProductInfo> content, long totalElements) {
}
```

읽을 때 `new PageImpl<>(content, pageable, totalElements)`로 재구성한다. `pageable`은 캐시 키를 만들 때 쓴 값을 호출부가 그대로 들고 있으므로 캐시 값에는 담지 않는다.

## TTL

| 캐시 | TTL | 이유 |
|---|---|---|
| 상세 | 5분 | 무효화가 즉시 반영되므로 TTL은 "무효화 누락 시 안전망" 역할만 한다. 길게 잡아도 안전하다. |
| 목록 | 30초 | 무효화를 하지 않으므로 TTL이 유일한 신선도 보장 수단이다. `LIKES_DESC` 정렬은 좋아요가 몰리는 인기 상품일수록 변경 빈도가 높아 너무 길게 잡으면 순위 체감 지연이 커진다. |

두 캐시의 TTL이 다른 근본 이유는 "무효화 여부"다 — **상세는 무효화가 있어서 TTL을 길게 잡아도 위험이 적고, 목록은 무효화가 없어서 TTL이 곧 staleness의 상한이라 짧게 잡아야 한다.**

### 상세 캐시 5분 — "무효화가 실패했을 때"를 위한 값

상세 캐시는 5개 쓰기 경로 전부에 무효화 이벤트가 걸려 있다. 정상 동작하는 한 TTL이 끝나기 전에 항상 무효화가 먼저 일어나므로, 정상 상황에서 TTL 값 자체는 거의 의미가 없다.

TTL이 실제로 의미를 갖는 건 무효화가 **누락되는 경우**다.

- 향후 새로운 쓰기 경로(예: 가격 일괄 변경 배치)를 추가하면서 `ProductCacheEvictEvent` 발행을 빠뜨리는 구현 누락
- Redis 마스터 장애로 `evictAll()` 호출이 예외를 던지고 fail-open 정책에 따라 그냥 무시되는 경우
- 애플리케이션 배포/재시작 타이밍에 이벤트 리스너가 처리되지 못하는 경우

이럴 때 캐시는 무효화되지 않은 채 옛 값으로 남는데, TTL이 있으면 늦어도 5분 후엔 강제로 사라지고 다음 요청이 DB에서 새로 읽어와 캐시를 다시 채운다.

**왜 5분인가**: 더 짧게 잡으면(예: 30초) 무효화가 잘 동작하는 정상 상황에서도 자주 만료돼, 무효화로 절약했어야 할 DB 조회를 다시 자주 하게 된다 — 캐시 효율만 깎인다. 더 길게 잡으면(예: 1시간) 무효화 누락이 실제로 발생했을 때 사용자가 옛 가격/재고를 1시간 동안 보게 된다. 5분은 "무효화가 정상일 땐 거의 영향 없고, 무효화가 실패했을 때는 사람이 알아차리고 대응할 정도의 시간 안에 자동 복구되는" 절충값이다.

### 목록 캐시 30초 — 무효화가 없으니 이게 신선도의 전부

목록 캐시는 무효화를 걸지 않는다(브랜드×정렬×페이지 조합이 너무 많아 특정 키만 골라 지우는 게 비현실적이라는 게 앞 섹션의 결론이다). 그러니 캐시에 들어간 데이터가 얼마나 오래될 수 있는지를 결정하는 변수가 TTL 하나뿐이다.

`LIKES_DESC` 정렬이 특히 문제인 이유는, 좋아요는 인기 상품일수록 분 단위가 아니라 초 단위로 누적되기 때문이다. 좋아요를 누른 직후 목록으로 돌아왔을 때 순위가 그대로면 "방금 눌렀는데 왜 안 바뀌지"라는 체감 불만이 생긴다.

**왜 30초인가**: 더 길게 잡으면(예: 5분) 이 체감 지연이 그만큼 길어진다. 더 짧게 잡으면(예: 5초) 캐시가 너무 자주 만료돼 적중률이 떨어지고, 애초에 캐시를 도입한 이유(조합 조회 비용 절감)가 옅어진다. 30초는 "사용자가 체감하기엔 짧고, DB 부하를 의미 있게 줄이기엔 충분히 긴" 지점으로 잡은 값이다.

### 주의

5분/30초는 모니터링 없이 정한 초기값이다. 실제 트래픽에서 적중률/DB 부하를 관찰한 뒤 조정하는 것을 전제로 한다 (`like-count-refactoring-plan.md`의 "향후 고려" 섹션과 같은 태도).

## 무효화 전략 — 상세 캐시

### 쓰기 경로 전체 목록

`ProductInfo`는 `name`/`price`(상품), `inStock`(재고), `likeCount`(product_stats) 네 가지 값을 조합한다. 이 값을 바꾸는 쓰기 경로는 `ProductFacade` 바깥에도 있다 — 빠뜨리면 그 경로로 바뀐 값은 TTL이 끝날 때까지 stale 상태로 남는다.

| # | 위치 | 바뀌는 값 | 비고 |
|---|---|---|---|
| 1 | `ProductFacade.updateProductForAdmin` | name, price, inStock | admin이 상품/재고를 함께 수정 |
| 2 | `ProductFacade.deleteProduct` | soft delete | 캐시에 남아있으면 삭제된 상품이 계속 조회됨 |
| 3 | `OrderFacade.createOrder` → `StockService.decreaseStock` | inStock | **`ProductFacade`를 거치지 않는다.** 주문 시 재고가 직접 차감된다 |
| 4 | `LikeOutboxEventListener.handle` | likeCount | **비동기.** Outbox 처리 시점에 반영되며 `ProductFacade`와 무관하다 |
| 5 | `BrandFacade.deleteBrand` | soft delete (다건) | 브랜드 삭제 시 소속 상품 전체와 재고가 함께 soft delete된다. productId 여러 건을 한 번에 무효화해야 한다 |

(3), (5)는 `ProductFacade`를 호출하지 않으므로 캐시 무효화 책임을 누락하기 가장 쉬운 지점이다.

### 이벤트 기반 무효화

위 5개 지점이 캐시 컴포넌트를 직접 호출하면 `OrderFacade`, `BrandFacade`, `LikeOutboxEventListener`, `ProductFacade`가 모두 캐시 구현에 의존하게 된다. 이미 `LikeCountChangedEvent`로 이벤트 기반 비동기 처리를 쓰고 있으므로 같은 패턴을 재사용한다.

```
application/product/ProductCacheEvictEvent.java
  record ProductCacheEvictEvent(List<Long> productIds)

application/product/ProductCacheEvictListener.java
  @TransactionalEventListener(phase = AFTER_COMMIT)
  evictAll(event.productIds())  — best-effort, 예외는 로그만 남기고 무시
```

5개 쓰기 경로는 각자의 트랜잭션 안에서 `ApplicationEventPublisher.publishEvent(new ProductCacheEvictEvent(...))`만 호출한다. 캐시 구현을 몰라도 되고, 새 캐시 백엔드로 바꾸더라도 리스너 하나만 바꾸면 된다.

**`AFTER_COMMIT`이어야 하는 이유**: 트랜잭션 커밋 전에 캐시를 지우면, 그 사이에 들어온 읽기 요청이 커밋 전(=아직 옛 값) 상태를 다시 캐시에 채워 넣을 수 있다. 이후 트랜잭션이 커밋되면 캐시는 옛 값으로 덮인 채 TTL 끝까지 남는다. 커밋 이후에 지우면 그다음 읽기는 이미 커밋된 새 값으로 캐시를 채운다.

`LikeOutboxEventListener`는 이미 `@Transactional`이므로, 그 메서드 안에서 `ProductCacheEvictEvent`를 발행해도 실제 무효화는 `ProductCacheEvictListener`가 `AFTER_COMMIT` 시점에 별도로 수행한다.

### 목록 캐시는 왜 무효화하지 않는가

상품 1건의 변경이 영향을 미칠 수 있는 목록 캐시 키는 브랜드 유무 2가지 × 정렬 3가지 × 페이지 수만큼이라 특정 키만 골라 지우는 게 불가능하다. 전체 목록 캐시를 통째로 비우는 방식도 검토했으나, `LIKES_DESC` 갱신처럼 좋아요 하나가 등록될 때마다 모든 목록 캐시가 날아가면 캐시 적중률이 거의 0에 가까워진다. 짧은 TTL(30초)로 staleness를 허용하는 쪽이 더 합리적이다.

## 장애 대응 (Fail-open)

`ProductCacheStore`의 모든 읽기/쓰기/삭제는 Redis 연결 실패를 캐시 미스로 간주하고 흐름을 막지 않는다.

```java
try {
    return Optional.ofNullable(redisTemplate.opsForValue().get(key))
        .map(json -> deserialize(json, ...));
} catch (Exception e) {
    log.warn("상품 캐시 조회 실패. key={}", key, e);
    return Optional.empty();
}
```

쓰기/삭제도 동일하게 예외를 흡수하고 로그만 남긴다. Redis가 죽어도 `ProductFacade`는 항상 DB 조회로 폴백해 응답한다. `ProductCacheEvictListener`도 같은 원칙으로, 무효화 실패가 원본 트랜잭션(좋아요/주문/admin 수정)에 영향을 주면 안 된다 — 이미 `AFTER_COMMIT`이라 원본 트랜잭션과 분리되어 있지만, 리스너 내부 예외가 상위로 전파되지 않도록 한 번 더 감싼다.

## 레이어 배치

```
application/product/
  ProductCacheEvictEvent.java
  ProductCacheEvictListener.java
infrastructure/product/
  ProductCacheStore.java       (RedisTemplate 직접 사용, @Component)
  ProductListCacheValue.java
```

**`ProductCacheStore`를 domain 인터페이스로 추상화하지 않는다.** CLAUDE.md 컨벤션상 "Repository 인터페이스는 domain에, 구현체는 infrastructure에"가 원칙이지만, 이 규칙은 영속성 저장소(시스템의 정합성 원본)를 가정한 것이다. 캐시는 시스템의 정합성 원본이 아니라 `ProductFacade`의 조합 비용을 줄이는 응용 계층 최적화이므로, `Repository`라는 이름과 추상화를 그대로 가져오면 "캐시도 영속 저장소처럼 신뢰해야 하는 것"이라는 잘못된 인상을 준다. `ProductFacade`가 `infrastructure/product/ProductCacheStore`를 직접 주입받아 쓴다.

## 읽기 흐름

```
ProductFacade.getProduct(id)
  1. productCacheStore.findProduct(id) → 있으면 즉시 반환
  2. 없으면 기존 조합 로직 수행
  3. productCacheStore.putProduct(id, info)
  4. 반환

ProductFacade.getProducts(brandId, pageable)
  1. 캐시 키 조립: brandId, sort, page, size
  2. productCacheStore.findList(key) → 있으면 PageImpl로 복원해 즉시 반환
  3. 없으면 기존 조합 로직 수행 (A3/B3 분기 포함)
  4. productCacheStore.putList(key, content, totalElements)
  5. 반환
```

## 알려진 트레이드오프

- **Replica 읽기 지연**: 캐시에 쓰는 `defaultRedisTemplate`은 `REPLICA_PREFERRED`다. 무효화(삭제)는 master에 즉시 반영되지만 그 직후의 읽기가 복제 지연 중인 replica를 보면 짧은 시간 동안 무효화 이전 상태를 다시 캐시에 채울 수 있다. TTL이 이 경우의 최종 안전망이 된다.
- **상세 캐시 5개 쓰기 경로 결합**: 이벤트로 결합도를 낮췄지만, 새로운 쓰기 경로(예: 향후 추가될 할인가 일괄 변경 배치)가 생기면 그 경로에서도 `ProductCacheEvictEvent`를 발행해야 한다는 사실을 깜빡하기 쉽다. 이 표(쓰기 경로 전체 목록)를 갱신 기준으로 유지한다.
- **목록 캐시 staleness**: 좋아요 등록 직후 30초 동안 `LIKES_DESC` 목록 순위가 뒤처질 수 있다. 상세 페이지(무효화 적용)와 목록 페이지(TTL만) 사이에 짧은 불일치가 생길 수 있음을 인지하고 받아들인다.

## 구현 작업 목록

1. `infrastructure/product/ProductCacheStore.java` — RedisTemplate 기반 get/put/evict, 예외 흡수
2. `infrastructure/product/ProductListCacheValue.java` — 목록 캐시 직렬화 모델
3. `application/product/ProductCacheEvictEvent.java`
4. `application/product/ProductCacheEvictListener.java` — `@TransactionalEventListener(AFTER_COMMIT)`
5. `ProductFacade.getProduct` / `getProducts` — 캐시 조회·적재 추가
6. `ProductFacade.updateProductForAdmin` / `deleteProduct` — 이벤트 발행 추가
7. `OrderFacade.createOrder` — 재고 차감 후 이벤트 발행 추가
8. `BrandFacade.deleteBrand` — productIds 전체에 대해 이벤트 발행 추가
9. `LikeOutboxEventListener.handle` — increase/decreaseLikeCount 후 이벤트 발행 추가
10. 테스트
    - `ProductCacheStoreIntegrationTest` — 로컬 docker-compose Redis 기준(기존 통합 테스트 관행과 동일하게 Testcontainers는 쓰지 않음) get/put/evict 확인, TTL은 `RedisTemplate.getExpire()`로 설정값만 검증(운영 클래스에 테스트 전용 TTL 주입 통로를 추가하지 않기 위함)
    - `ProductFacadeIntegrationTest` — 캐시 히트 시 DB 재호출 없이 반환되는지, 무효화 후 갱신된 값이 반환되는지
    - 공유 Redis 컨테이너는 그대로 두고, 잘못된 포트로 구성한 `ProductCacheStore`를 `@TestConfiguration`으로 `@Primary` 오버라이드해 `ProductFacade` 호출이 여전히 정상 응답하는지 확인 (fail-open)

## 참고

- `docs/week5/like-count-refactoring-plan.md` — 이번에 재사용하는 이벤트 기반 비동기 패턴(`LikeCountChangedEvent`)의 선행 설계
- `modules/redis/src/main/java/com/loopers/config/redis/RedisConfig.java` — `defaultRedisTemplate`(REPLICA_PREFERRED), `masterRedisTemplate`(MASTER) 빈 정의
