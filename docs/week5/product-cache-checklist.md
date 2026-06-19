# 상품 API 캐시 적용 구현 체크리스트

> 상세 설계: [product-cache-design.md](../design/product-cache-design.md)

---

### 1. ProductCacheStore (캐시 컴포넌트)

- [x] `infrastructure/product/ProductCacheStore.java` 생성 (`@Component`, domain 인터페이스 없이 직접 사용)
  - [x] `findProduct(Long productId): Optional<ProductInfo>`
  - [x] `putProduct(Long productId, ProductInfo info)` — TTL 5분
  - [x] `evictProduct(Long productId)`
  - [x] `evictAll(Collection<Long> productIds)`
  - [x] `findList(String key): Optional<ProductListCacheValue>`
  - [x] `putList(String key, ProductListCacheValue value)` — TTL 30초
  - [x] `defaultRedisTemplate`(`@Primary`, REPLICA_PREFERRED) 주입
  - [x] `ObjectMapper`(`supports:jackson` 자동 구성 빈) 주입해 JSON 직렬화/역직렬화
  - [x] 모든 메서드 try-catch로 예외 흡수, 실패 시 로그(`warn`) + `Optional.empty()`/무시 — fail-open
  - [x] 캐시 키 빌더: `product:detail:{productId}`, `product:list:{brandId|"all"}:{sort}:{page}:{size}`

### 2. ProductListCacheValue (목록 캐시 직렬화 모델)

- [x] `infrastructure/product/ProductListCacheValue.java` 생성
  - [x] `record ProductListCacheValue(List<ProductInfo> content, long totalElements)`
  - [x] `PageImpl` 자체를 직렬화하지 않음 — 읽기 시 `new PageImpl<>(content, pageable, totalElements)`로 호출부에서 재구성

### 3. ProductCacheEvictEvent

- [x] `application/product/ProductCacheEvictEvent.java` 생성
  - [x] `record ProductCacheEvictEvent(List<Long> productIds)`

### 4. ProductCacheEvictListener

- [x] `application/product/ProductCacheEvictListener.java` 생성
  - [x] `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`
  - [x] `productCacheStore.evictAll(event.productIds())`
  - [x] 리스너 내부 예외도 흡수 — 무효화 실패가 원본 트랜잭션에 영향 주지 않도록

### 5. ProductFacade — 캐시 조회/적재

- [x] `getProduct(id)`
  - [x] `productCacheStore.findProduct(id)` 히트 시 즉시 반환
  - [x] 미스 시 기존 조합 로직 수행 후 `putProduct(id, info)`
- [x] `getProducts(brandId, pageable)`
  - [x] 캐시 키 조립(brandId, sort, page, size) 후 `findList(key)` 히트 시 `PageImpl`로 복원해 반환
  - [x] 미스 시 기존 조합 로직(A3/B3 분기 포함) 수행 후 `putList(key, ...)`

### 6. ProductFacade — 무효화 이벤트 발행

- [x] `updateProductForAdmin(id, ...)` — 끝에 `eventPublisher.publishEvent(new ProductCacheEvictEvent(List.of(id)))`
- [x] `deleteProduct(id)` — 끝에 동일하게 발행

### 7. OrderFacade — 무효화 이벤트 발행

- [x] `createOrder(...)` — `stockService.decreaseStock` 루프 종료 후, 주문에 포함된 `productIds` 전체로 `ProductCacheEvictEvent` 발행
  - [x] inStock이 바뀌는 상품 전부를 빠뜨리지 않는지 확인 (productFacade를 거치지 않는 경로라 누락하기 쉬움)

### 8. BrandFacade — 무효화 이벤트 발행

- [x] `deleteBrand(id)` — soft delete 대상 `productIds` 전체로 `ProductCacheEvictEvent` 발행
  - [x] 브랜드 소속 상품이 0건이어도 빈 리스트 발행은 생략(불필요한 이벤트 방지)

### 9. LikeOutboxEventListener — 무효화 이벤트 발행

- [x] `handle(LikeCountChangedEvent event)` — `markDoneIfPending`이 true이고 `increase/decreaseLikeCount` 처리된 경우에만 `ProductCacheEvictEvent(List.of(event.productId()))` 발행
  - [x] `markDoneIfPending`이 false로 조기 종료되는 경로에는 이벤트를 발행하지 않음(이미 처리된 케이스라 캐시도 이미 무효화됨)

### 10. 테스트

- [x] `ProductCacheStoreIntegrationTest` (로컬 docker-compose 인프라 + `RedisCleanUp` — 기존 통합 테스트 관행과 동일하게 `RedisTestContainersConfig`는 사용하지 않음)
  - [x] `putProduct` 후 `findProduct` 히트 확인
  - [x] `evictProduct` 후 `findProduct` 미스 확인
  - [x] `putList`/`findList` 히트 확인, `ProductListCacheValue` → `PageImpl` 복원 검증은 `ProductFacadeIntegrationTest`에서
  - [x] TTL이 올바르게 설정되는지 확인 (테스트 전용 오버로드를 운영 클래스에 추가하는 대신, 테스트에서 `RedisTemplate.getExpire()`로 직접 확인 — 자연 만료 대기 없이 검증)
- [x] `ProductFacadeIntegrationTest` (기존 파일에 케이스 추가)
  - [x] `getProduct` 캐시 히트 시 DB 변경 없이도 캐시된 값 반환 (예: 캐시 적재 후 DB 직접 수정 → 캐시 값이 그대로 반환되는지로 "캐시를 타고 있다" 증명)
  - [x] `updateProductForAdmin` 호출 후 `getProduct` 호출 시 갱신된 값 반환 (무효화 확인)
  - [x] `deleteProduct` 호출 후 `getProduct` 호출 시 NOT_FOUND (무효화 확인)
  - [x] `getProducts` 캐시 히트 시 `totalElements` 포함 동일 결과 반환
- [x] `OrderFacadeIntegrationTest` (기존 파일에 케이스 추가)
  - [x] 주문 전 `getProduct` 캐시 적재 → 주문(재고 차감) → `getProduct` 재호출 시 `inStock` 최신 반영 확인
- [x] `BrandFacadeIntegrationTest` (기존 파일에 케이스 추가)
  - [x] 소속 상품 여러 건 캐시 적재 → 브랜드 삭제 → 각 상품 `getProduct` 호출 시 NOT_FOUND 또는 최신 상태 반영 확인
- [x] `LikeFacadeIntegrationTest` 또는 `LikeOutboxProcessorIntegrationTest` (기존 파일에 케이스 추가)
  - [x] `getProduct` 캐시 적재 → like → `processor.process()` → `getProduct` 재호출 시 `likeCount` 최신 반영 확인
- [x] fail-open 검증 (신규 통합 테스트 `ProductFacadeFailOpenIntegrationTest`)
  - [x] 공유 Redis 컨테이너는 그대로 두고, 잘못된 포트로 구성한 `ProductCacheStore`를 `@TestConfiguration`으로 `@Primary` 오버라이드 — `ProductFacade.getProduct`/`getProducts` 호출 시 예외 없이 DB 폴백으로 정상 응답하는지 확인
- [x] 캐시 도입으로 인한 기존 테스트 격리 보강: `ProductV1ApiE2ETest`에 `RedisCleanUp` 추가 (목록 캐시가 TTL 동안 다음 테스트로 누수되어 `returnsAllProducts_whenProductsExist`가 실패하는 것을 확인 후 수정)
