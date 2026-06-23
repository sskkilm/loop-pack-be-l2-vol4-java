# 결제 API 아키텍처 (1차 확정)

> 범위: 엔드포인트/컴포넌트 구성과 각 컴포넌트의 책임 경계만 확정한다. Order 상태 전이, 콜백 중복·동시성 처리는 의도적으로 다음 문서로 미룬다.

## 판단 기준

PG(`pg-simulator`)는 비동기 결제다. 결제 요청을 보내도 그 자리에서 결과를 알 수 없고, 최종 결과는 1~5초 뒤 콜백으로 통보된다.

이 구조에서 핵심 기준 하나로 모든 컴포넌트의 역할이 정해진다.

> **유저 요청 경로는 외부(PG) 호출에 의존하지 않는다. PG와의 상태 동기화는 유저 요청과 분리된 별도 채널(콜백/스케줄러)에서만 일어난다.**

유저가 보낸 요청이 PG 응답을 기다리는 순간은 "결제 요청" 단 한 번뿐이고, 그 외의 모든 유저 요청은 우리 DB만 읽는다. PG와 우리 시스템 간의 동기화는 콜백 수신과 스케줄러, 이 두 경로로만 일어난다.

## 컴포넌트 구성

| # | 컴포넌트 | 호출자 → 호출 대상 | 외부(PG) 호출 여부 |
|---|---|---|---|
| 1 | `POST /api/v1/payments` (결제 요청) | 유저 → 커머스 서버 | O — PG에 결제 요청, `transactionKey`+`PENDING` 응답을 그대로 반환 |
| 2 | `GET /api/v1/payments/{transactionKey}` (결제 상태 조회) | 유저 → 커머스 서버 | X — 우리 DB(Payment)만 조회 |
| 3 | `POST /api/v1/payments/callback` (콜백 수신) | PG → 커머스 서버 | X — PG가 보낸 결과를 받아 우리 DB 갱신 |
| 4 | 스케줄러 (reconciliation) | 커머스 서버 → PG | O — PENDING 건을 PG 조회 API로 동기화 |

1·2는 유저 요청 경로, 3·4는 PG-커머스 서버 간 동기화 경로다. 2는 "외부 호출 없이 DB만 읽는다"는 기준의 핵심 사례이고, 4는 "PG 호출이 필요하면 유저 요청 바깥에서 한다"는 기준의 핵심 사례다.

## 컴포넌트별 결정 근거

### ① 결제 요청 — PENDING을 그대로 반환

PG가 동기 결과를 주지 않으므로 우리도 줄 수 없다. `transactionKey`와 `PENDING` 상태로 Payment를 저장하고, 받은 그대로 유저에게 반환한다.

### ② 결제 상태 조회 — DB만 읽는다, PG를 호출하지 않는다

확인된 것은 PG사들이 **웹훅을 1차 동기화 수단으로 권장한다**는 점이다.

- Stripe: *"It's technically possible to use polling instead of webhooks ... but doing so is much less reliable and might cause rate limiting issues."* ([Stripe Docs](https://docs.stripe.com/payments/payment-intents/verifying-status))
- 포트원: *"webhook은 리소스나 통신 측면에서 훨씬 더 효율적입니다."* ([PortOne Docs](https://developers.portone.io/opi/ko/integration/webhook/readme-v2?v=v2))
- 토스페이먼츠: 폴링을 금지하지는 않되, 권장 주기를 안내한다 — *"API 폴링은 주기를 60초에서 120초로 설정하는 것을 가장 추천하는데요."* ([토스페이먼츠 개발자센터](https://docs.tosspayments.com/resources/glossary/webhook))

세 PG사 모두 입장은 같다 — 웹훅이 1차 수단이고, 폴링은 금지 대상이 아니라 빈도를 낮춰서 쓰는 보조 수단이다.

이 구조를 채택한 이유는 위 인용에서 직접 확인되는 부분과, 그걸 토대로 한 추론을 구분해서 적는다.

1. (확인됨) 웹훅이 1차 동기화 수단이라는 점은 세 PG사 문서에서 공통으로 확인된다.
2. (추론) "유저 요청 한 건마다 그 요청 안에서 PG를 동기 호출하는" 패턴은 위 문서들에 직접 등장하지 않았다. 다만 이는 그런 패턴이 명시적으로 비권장된다는 뜻이 아니라, 검색 범위 안에서 발견하지 못했다는 뜻이다.
3. (추론) 콜백 유실에 대비한 폴링이 필요하다면, 토스페이먼츠가 안내하는 것처럼 빈도를 낮춰 스케줄러로 수행하는 쪽이 유저 요청 경로에 매번 끼워넣는 것보다 합리적이다.

### ③ 콜백 수신 — PG가 호출하는 엔드포인트

호출자가 유저가 아니라 PG라는 점만 다를 뿐, 이것도 엔드포인트다. PG가 결제 결과를 비동기로 통보하는 표준 채널이며, 우리 쪽 상태 갱신의 1차 수단이다.

### ④ 스케줄러 — 콜백 유실에 대한 보조 수단

콜백은 유실될 수 있다(네트워크 문제, 커머스 서버 다운 등). 스케줄러는 일정 시간 이상 PENDING으로 남아있는 건을 찾아 PG 조회 API로 직접 동기화한다.

- 폴링 대상 기준: pg-simulator의 최대 처리 시간(5초)보다 길게 잡아야 한다. 정상 흐름이면 5초 안에 콜백으로 상태가 바뀌므로, 그보다 오래 PENDING인 건만 "콜백이 안 온 것"으로 간주해 폴링 대상으로 삼는다.
- 폴링 주기 자체는 이 문서에서 확정하지 않는다. UX 관점에서는 짧을수록 유리하지만, 정확한 값은 PG API 부하·운영 요구사항을 따져야 하므로 숫자는 미정으로 남긴다.

## 의도적으로 남겨둔 후속 논의

이 구조를 깨지 않는 선에서, 다음 두 가지는 별도로 다룬다.

1. **Order 상태 전이** — 결제 SUCCESS 콜백을 받았을 때 Order를 언제, 어떤 트랜잭션 경계로 갱신할지. Payment와 Order의 연결 방식도 함께 정해야 한다.
2. **콜백 중복·동시성** — 동일 `transactionKey`로 콜백이 두 번 오는 경우(PG 재전송 등)의 멱등 처리, 그리고 콜백 처리와 스케줄러가 같은 Payment 건을 동시에 갱신하려 할 때의 충돌 처리.

## 참고 자료

- [Stripe — Verifying payment status](https://docs.stripe.com/payments/payment-intents/verifying-status)
- [토스페이먼츠 — 웹훅](https://docs.tosspayments.com/resources/glossary/webhook)
- [포트원 — 웹훅 연동](https://developers.portone.io/opi/ko/integration/webhook/readme-v2?v=v2)
- [Integrate.io — Webhook best practices](https://www.integrate.io/blog/apply-webhook-best-practices/)
- [Hookdeck — Common outbound webhook mistakes](https://hookdeck.com/outpost/guides/common-outbound-webhook-mistakes)
- [Merge.dev — API polling best practices](https://www.merge.dev/blog/api-polling-best-practices)
