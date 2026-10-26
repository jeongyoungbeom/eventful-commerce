# Eventful Commerce 개발 현황·다음 개발·전체 코드 감사

검토일: 2026-10-26 (Asia/Seoul)  
검토 대상: `/home/jybeomss/sideProject`  
현재 HEAD: `96c0e240420ef003e363d7078410dc3a34abc741`  
리뷰 유형: active TASK가 없는 ad-hoc 독립 리뷰 (`workflow-independent-review`)

이 저장소는 Kafka 이벤트로 상품·주문·결제·배송·정산·알림을 연결하는 커머스 백엔드다. 현재는 입점 판매자 중심의 구매 흐름이 구현되어 있다. 여러 구매자·판매자의 동시 요청, Kafka 중복 전달·재시도, 여러 서비스 인스턴스가 가능한 기존 설계를 기준으로 점검했다. 처리량 수치는 측정하지 않았다.

## 검토 범위와 근거

- 요구사항 출처: 이번 사용자의 전체 현황·코드 점검 요청, `AGENTS.md`, README, 현재 TASK, 적용되는 DECISIONS, 기존 정합성 보완 계획, 2026-10-20 재고 확장 계획·설계.
- 범위: 8개 실행 서비스와 3개 공통 모듈. 인증·계정, 상품·주문 가격, 재고 Lua·보상, 결제·환불, 배송 취소, 정산, Outbox·멱등성·DLT, 알림 재시도, Gateway·Nginx·Compose와 관련 테스트를 살폈다.
- 깊이: 주요 입력·상태 변경·동시성·실패 복구 경로를 집중적으로 읽었다. Kotlin 제품 소스 244개·테스트 파일 35개를 목록화했지만 모든 DTO·Swagger 문구·시나리오 스크립트를 줄 단위로 정독한 것은 아니다.
- 수행한 검증: S6 당시 소스·빌드 설정 해시 348개와 현재 파일을 대조했고 차이 0개였다. 보존된 S4/S5/S6 XML을 직접 집계했다. 기존 빌드 클래스와 현재 소스 흐름을 대조한 뒤 임시 Java 진단으로 실제 서비스 메서드의 다섯 결함을 재현하고 Spring 트랜잭션 롤백 규칙을 한 건 확인했다. 실제 Hibernate 매핑으로 상품 UPDATE SQL을 DB 접속 없이 생성했다. Nginx CORS와 주문 필수 헤더를 대조했다. `git diff --check`가 통과했다.
- 검토 제외·미검증: 전체 Compose 기동, 실제 Kafka 전송·재균형·DLT, 이번 세션의 실제 PostgreSQL/Redis 경합, 프로세스 강제 종료·재시작, 외부 PG·메일·Telegram·택배 연동, 브라우저 실동작, 부하·침투·의존성 CVE 검사. 비밀 설정 파일 내용은 읽지 않았다.
- 변경 범위: 제품 코드·기존 테스트·TASK·계획·설계 결정은 수정하지 않았다. 이 리뷰와 임시 진단 결과만 작성했다.

## 1. 어디까지 개발됐는가

**기존 입점 커머스의 핵심 백엔드와 정합성 보완 S1~S6는 구현·독립 리뷰까지 완료됐다. 직매입·창고 재고 확장은 설계만 있고 제품 구현은 시작되지 않았다.**

| 영역 | 현재 확인한 구현 |
|---|---|
| 계정·인증 | 일반 사용자/판매자 가입·로그인, JWT, 갱신 토큰, 로그아웃, 비밀번호 재설정, 로그인·요청 제한 |
| 상품 | 판매자 상품 등록·수정·비활성화, 이미지, 재고 증감, 주문용 ProductReadModel |
| 주문·재고 | 다상품·다판매자 주문, 품절 품목 제외 부분 성공, 요청 멱등성, Redis 예약·확정·해제·복원, 만료·보상 |
| 결제·환불 | 결제 레코드, 금액·결과·거래 ID 검증, 판매자별 취소 대기, 환불 한도·중복 방지 |
| 배송 | 판매자 주문별 준비·출고·완료, 배송 취소 승인/거절, 대기 상태 복구. 운송장과 완료는 시뮬레이션 |
| 정산 | 판매자별 정산, 환불 ID 기준 중복 차감 방지, 행 잠금·version, 배치 키 기반 조회와 조건부 확정 |
| 이벤트·운영 | Outbox 재시도·claim, 소비자 멱등성, DLT 보관·재전송, 재고/보상 대사, Telegram 재시도, Prometheus/Grafana |

기존 결함 보완 계획의 여섯 단계는 환불 중복 → 결제 경합 → 부분 취소·예약 만료 → 배송 취소 → 배치 누락 → 웹훅 검증 순으로 완료됐다. 최신 TASK는 `EC-CORRECTNESS-RESTART-20261007-S6`, `completed`; HANDOFF는 `inactive`다.

**실서비스 결제는 아직 준비되지 않았다.** `PaymentWebhookVerifier`의 실제 PG 구현이 없어 실제 웹훅은 503으로 차단된다. 별도 프로필·토큰·Gateway JWT를 요구하는 로컬 시뮬레이션은 있다. 실제 PG 승인/환불 API, 실제 택배 연동, 은행 지급은 구현 완료로 볼 수 없다. 정산 지급도 현재는 내부 상태 변경이다. 이는 이전 단계에서 명시적으로 제외한 범위이므로 아래 신규 결함에 포함하지 않았다.

관리자 엔티티·개발용 초기 계정은 있지만 공개 로그인 API는 USER/SELLER만 제공한다. 직매입 관리자 업무의 인증 경로는 다음 S1에서 구체화해야 한다. 전체 사용자용 프런트엔드도 현재 저장소의 구현 범위가 아니다.

Git에는 추적 파일 수정 56개와 미추적 항목 26개가 남아 있다. 미추적 항목은 디렉터리가 합쳐진 `git status --porcelain` 기준이며 실제 파일 수가 아니다. 마지막 커밋은 2026-10-07이므로, 10월 19일 완료분을 현재 HEAD만으로 재현할 수는 없다.

## 2. 다음 개발은 무엇인가

2026-10-20에 저장된 계획은 `EC-INVENTORY-EXPANSION-20261020`이다. 단계표의 S1~S8은 모두 **미착수**다. `settings.gradle.kts`에도 `inventory-service`가 없다.

| 순서 | 예정 작업 |
|---|---|
| **S1 — 바로 다음** | 공식 판매 주체·입점/직매입 유형, 상품-SKU 1:1 계약, 주문 스냅샷, 이벤트/DTO, 내부·관리자 인증 경계 |
| S2 | 별도 inventory-service·PostgreSQL, 두 창고, 로트·사용기한, 공급사·발주·분할 입고·원장 |
| S3 | 한 품목을 한 창고에서 전량 예약, 사용기한 우선 로트 선택, 조회·해제·만료·할당·복구 |
| S4 | 기존 Redis와 재고 API를 조율한 혼합 주문, 품목별 부분 성공, 고객 최종 확인 |
| S5 | 단일 결제와 직매입 품목 취소·환불, 배송비 배분, 일반 판매자 정산과 직매입 분리 |
| S6 | 창고별 피킹·포장·출고·배송·기본 반품, 실제 로컬 전체 플로우를 명령 하나로 검증 |
| S7 | 창고 간 이동·부분 도착·미도착 복구 |
| S8 | 실사·대사·운영 조회·최소 관리자 화면 |

근거: `.workflow/INVENTORY_EXPANSION_PLAN.md`, `docs/inventory-expansion-design.md`, `.workflow/DECISIONS.md` D-011~D-019. 새로운 서비스부터 바로 만들기보다 S1에서 기존 입점 경로와 직매입의 구분 계약을 먼저 구현하도록 계획되어 있다.

**검토 후 권장 순서:** 아래 가격·재고·인증·브라우저 주문 결함을 먼저 보완하고 회귀 검증 → 현재 완료분을 검토 가능한 Git 변경 묶음으로 정리 → 예정된 S1 진입. 실제 PG 연동은 기존 확장 계획의 선행 조건이 아니며 보호된 시뮬레이션을 유지할 수 있다. 이번 리뷰에서 개발 순서 문서를 변경하거나 새 TASK를 활성화하지는 않았다.

## 3. 수정이 필요한 문제

### REV-001 — 상품 가격 변경이 새 주문에 전달되지 않음

- **심각도:** Major
- **위치:** `product-service/src/main/kotlin/com/eventfulcommerce/product/service/ProductService.kt:75`, `order-service/src/main/kotlin/com/eventfulcommerce/order/message/ProductEventsConsumer.kt:17`, `order-service/src/main/kotlin/com/eventfulcommerce/order/service/OrdersService.kt:160`.
- **문제·발생 조건:** 상품을 10,000원으로 등록한 뒤 20,000원으로 수정한다. `updateProduct()`는 상품 DB만 변경하고 Outbox를 기록하지 않으며, 주문 소비자는 등록·재고·비활성화만 처리한다.
- **영향:** 상품 조회에는 20,000원이 표시되지만 이후 새 주문은 등록 당시 10,000원으로 계산된다. 가격을 낮추면 더 높은 예전 가격으로 청구된다. 일시적인 이벤트 지연이 아니라 변경 전달 경로 자체가 없다.
- **근거:** 실제 `updateProduct()` 호출 후 반환 가격이 20,000원이고 Outbox 호출은 0회인 것을 확인했다. 주문의 단가·상품명은 로컬 읽기 모델을 사용한다. 기존 상품 테스트 5개에는 이 흐름의 검증이 없다.
- **권장 수정:** 상품 정보 변경을 같은 트랜잭션의 Outbox에 기록하고 주문 읽기 모델에 반영한다. 기존 주문의 가격 스냅샷은 보존하고, 변경 이후 새 주문의 가격을 검증하는 회귀 테스트를 추가한다.

### REV-002 — 상품 일반 수정이 동시에 갱신한 재고·비활성화를 덮어씀

- **심각도:** Major
- **위치:** `product-service/src/main/kotlin/com/eventfulcommerce/product/service/ProductService.kt:76`, 같은 파일 `:212`, `product-service/src/main/kotlin/com/eventfulcommerce/product/domain/entity/Product.kt:12`.
- **문제·발생 조건:** A가 상품명 수정용으로 stock=10인 상품을 읽고, B가 재고를 9로 변경·커밋한 뒤 A가 커밋한다. 재고 경로는 행 잠금을 쓰지만 일반 수정·비활성화의 조회는 잠금을 쓰지 않고 Product에 version 검사도 없다.
- **영향:** A가 오래된 stock=10을 덮어쓴다. 같은 방식으로 비활성화 상태가 ACTIVE로 되돌아갈 수 있다. 상품 DB와 Redis·주문 읽기 모델이 어긋나며, 재고만 잠그는 현재 방어로는 모든 writer를 보호하지 못한다.
- **근거:** 실제 Hibernate 6.5.3 매핑이 `update products set … status=?,stock=?,… where id=?`를 생성함을 오프라인 확인했다. 조건에 version이 없다. DB 경합 자체는 이번에 실행하지 않았다.
- **권장 수정:** 일반 수정·비활성화도 기존 `findByIdForUpdate()`로 조회해 모든 상품 변경에 동일한 행 잠금을 적용한다. 실제 PostgreSQL에서 정보 수정/재고 변경 및 정보 수정/비활성화의 교차 실행을 검증한다.

### REV-003 — 다른 Origin의 브라우저에서 주문할 수 없음

- **심각도:** Major
- **위치:** `nginx/nginx.conf:34`, `order-service/src/main/kotlin/com/eventfulcommerce/order/controller/OrderController.kt:87`.
- **문제·발생 조건:** 허용된 프런트엔드 Origin(예: localhost:3000)이 Nginx를 통해 주문하며 필수 `Idempotency-Key` 헤더를 보낸다. Nginx의 `Access-Control-Allow-Headers`에는 이 헤더가 없고 OPTIONS는 Nginx에서 204로 종료된다.
- **영향:** 브라우저의 사전 확인 요청이 CORS 정책을 만족하지 못해 실제 주문 요청을 차단한다. 헤더를 빼면 서버가 주문을 거부한다. curl·동일 Origin Swagger는 이 문제를 드러내지 않는다.
- **근거:** 주문 컨트롤러의 필수 검사와 Nginx 허용 헤더 목록을 코드로 대조했다. Nginx는 upstream의 CORS 허용 헤더도 숨기므로 Gateway 설정만 바꾸어 해결할 수 없다. 브라우저 실동작은 미실행이다.
- **권장 수정:** Nginx의 허용 헤더에 `Idempotency-Key`를 추가하고 해당 헤더를 포함한 OPTIONS와 실제 주문을 한 번 검증한다.

### REV-004 — 임의 전달 IP로 요청·로그인 제한을 회피할 수 있음

- **심각도:** Major
- **위치:** `nginx/nginx.conf:55`, `user-service/src/main/kotlin/com/eventfulcommerce/user/interceptor/RateLimitInterceptor.kt:74`, `user-service/src/main/kotlin/com/eventfulcommerce/user/service/AuthService.kt:200`.
- **문제·발생 조건:** 클라이언트가 `X-Forwarded-For`를 직접 넣으면 Nginx는 뒤에 실제 주소를 추가한다. 서비스는 검증 없이 가장 왼쪽 값을 제한 키와 감사 IP로 사용한다.
- **영향:** 헤더만 바꿔 IP별 요청 한도·로그인 한도를 회피하거나 다른 IP의 제한 카운터를 오염시킬 수 있다. 이메일 기준 제한은 별도로 남아 있으므로 이것만으로 모든 로그인 방어가 사라지는 것은 아니다.
- **근거:** 실제 RateLimitInterceptor에 `198.51.100.123, 203.0.113.2`를 주입했을 때 첫 주소가 RateLimitService에 전달되는 것을 확인했다. 로그인·감사 로그에도 같은 추출 방식이 있다.
- **권장 수정:** 현재 Nginx가 첫 외부 진입점이면 전달 헤더를 신뢰할 수 있는 실제 주소로 덮어쓰고, 서비스는 그 계약만 사용한다. 외부 프록시가 추가되면 신뢰할 프록시 목록을 명시한다.

### REV-005 — 비밀번호 재설정 후에도 기존 갱신 토큰으로 재로그인 가능

- **심각도:** Major
- **위치:** `user-service/src/main/kotlin/com/eventfulcommerce/user/service/PasswordResetService.kt:70`, `user-service/src/main/kotlin/com/eventfulcommerce/user/service/AuthService.kt:131`, `user-service/src/main/kotlin/com/eventfulcommerce/user/service/RefreshTokenService.kt:42`.
- **문제·발생 조건:** 기존 refresh token을 가진 상태에서 비밀번호 재설정을 완료한다. 재설정 토큰만 삭제하고 로그인 갱신 토큰은 남긴다. 재발급은 현재 비밀번호 변경 여부를 확인하지 않는다.
- **영향:** 탈취된 갱신 토큰으로 비밀번호 재설정 이후에도 새 access token을 받을 수 있다. 기본 refresh 수명은 7일이다.
- **근거:** 실제 PasswordResetService·RefreshTokenService·AuthService 메서드를 연결하고 저장소/JWT 검증을 Mock으로 대체한 진단에서 새 비밀번호 저장 뒤 기존 갱신 토큰의 access 재발급을 확인했다. 실제 HTTP/DB 통합 재현은 아니다.
- **권장 수정:** 비밀번호 변경 커밋 성공 시 해당 사용자의 refresh token을 폐기한다. 이미 발급된 access token의 즉시 무효화 여부도 정책으로 정하고 검증한다. 최소 회귀 조건은 재설정 전 refresh token으로 재발급이 거부되는 것이다.

### REV-006 — 실패 로그인 감사 기록과 DB 계정 잠금이 롤백됨

- **심각도:** Major
- **위치:** `user-service/src/main/kotlin/com/eventfulcommerce/user/service/AuthService.kt:179`, `user-service/src/main/kotlin/com/eventfulcommerce/user/service/AuditLogService.kt:14`, `user-service/src/main/kotlin/com/eventfulcommerce/user/service/AccountLockService.kt:25`.
- **문제·발생 조건:** 로그인 메서드의 트랜잭션 안에서 실패 로그·계정 잠금을 저장한 다음 RuntimeException인 InvalidCredentialsException을 던진다. AuditLogService는 기본 REQUIRED로 같은 트랜잭션에 참여한다.
- **영향:** 응답은 정상적으로 인증 실패지만 DB의 실패 감사 로그와 잠금 시각은 커밋되지 않는다. Redis 실패 카운터는 남으므로 일반 차단 전체가 사라지는 문제와는 구분해야 한다.
- **근거:** 호출 흐름·예외 상속·트랜잭션 전파를 확인하고 실제 Spring AnnotationTransactionAttributeSource로 InvalidCredentialsException이 로그인 트랜잭션의 롤백 대상임을 검증했다. 실제 DB 롤백 테스트는 이번에 실행하지 않았다.
- **권장 수정:** 실패 기록·계정 잠금을 실패 응답과 분리된 커밋 경계로 저장한다. 사용자와 판매자 모두 인증 실패 후 별도 DB 조회에서 로그·잠금이 유지되는지 검증한다.

### REV-007 — Redis 카운터 경합으로 보안 제한이 느슨해지거나 만료를 잃음

- **심각도:** Major
- **위치:** `user-service/src/main/kotlin/com/eventfulcommerce/user/service/LoginAttemptService.kt:24`, 같은 파일 `:41`, `user-service/src/main/kotlin/com/eventfulcommerce/user/service/RateLimitService.kt:53`.
- **문제·발생 조건:** 로그인 실패는 GET→+1→SET이므로 동시 요청이 같은 값을 덮어쓴다. 요청 제한도 조회/검사/증가가 분리되어 있고, 기존 키를 읽은 뒤 INCR 직전에 TTL이 만료되면 새 카운터에 TTL이 생기지 않는다.
- **영향:** 여러 실패가 한 번으로 집계되어 잠금이 늦어진다. 요청 제한 카운터가 만료 없는 키가 되는 경우에는 한도 도달 후 정상 사용자도 계속 429를 받을 수 있다.
- **근거:** 실제 LoginAttemptService의 두 스레드를 Mock Redis GET 지점에서 맞춰 실행해 두 실패가 최종 값 1로 저장되는 것을 재현했다. TTL 유실은 코드의 GET/INCR 순서와 Redis의 키 생성 동작으로 판단했으며 실제 Redis에서 실행하지 않았다.
- **권장 수정:** 기존 Redis에서 짧은 Lua로 한도 판단·증가·만료를 원자적으로 처리한다. 동시 요청 수 보존과 만료 경계 후 정상 복구를 검증한다. 새 인프라나 라이브러리는 필요하지 않다.

### REV-008 — 이미지 교체 실패가 기존 파일을 영구 삭제함

- **심각도:** Minor
- **위치:** `product-service/src/main/kotlin/com/eventfulcommerce/product/service/ProductService.kt:77`, `product-service/src/main/kotlin/com/eventfulcommerce/product/service/ImageStorageService.kt:22`.
- **문제·발생 조건:** 상품 이미지를 교체할 때 기존 파일부터 삭제하고 새 파일을 저장한다. 이후 파일 쓰기나 DB 커밋이 실패해도 파일 삭제는 DB 트랜잭션으로 복원되지 않는다.
- **영향:** 실패한 수정 요청만으로 기존 이미지가 사라지며 상품에는 삭제된 파일 URL이 남을 수 있다.
- **근거:** 임시 폴더의 실제 기존 파일을 사용하고 새 MultipartFile 저장에서 IOException을 발생시켰다. 메서드 실패 후 기존 파일이 없고 상품 URL은 그대로인 것을 확인했다.
- **권장 수정:** 새 파일을 먼저 저장하고 DB 커밋 성공 뒤 기존 파일을 삭제한다. 실패 시 새로 만든 파일만 정리한다. 중간 업로드 실패와 DB 커밋 실패의 파일 보존을 검증한다.

## 4. 테스트 상태와 판정의 한계

| 검증 자료 | 당시 테스트 수 | 실패 / 오류 / 생략 | 이번 처리 |
|---|---:|---|---|
| S4 주문·배송 | 112 | 0 / 0 / 0 | 저장된 XML 재집계 |
| S5 정산 | 47 | 0 / 0 / 0 | 저장된 XML 재집계 |
| S6 결제·Gateway·정산 환불 | 108 | 0 / 0 / 0 | 저장된 XML 재집계, 현재 소스 해시 대조 |

이 숫자는 서로 겹치는 과거 실행 결과이며 합산한 신규 테스트 통과 수가 아니다. 기존 전체 빌드나 테스트를 이번에 다시 실행했다고 주장하지 않는다. 소스가 같은 기존 성공 검증은 반복하지 말라는 저장소 지침에 따라 새 결함 진단에 집중했다.

신규 진단은 서비스 메서드 5건, 트랜잭션 규칙 1건, Hibernate SQL 생성 1건, CORS 계약 정적 검사 1건이다. 재현 성공은 결함을 관찰했다는 뜻이며 제품이 정상이라는 뜻이 아니다. DB·Kafka 통합 재현과 새 회귀 테스트는 각 수정 작업에서 필요하다.

특히 user-service에는 테스트 파일이 0개이며, 상품 테스트는 5개로 가격 변경·수정/재고 경합·파일 실패를 다루지 않는다. 이번에 문제가 나온 인증·상품 경로부터 회귀 검증을 추가하는 것이 효과적이다.

정산 스케줄러의 targetDate와 UTC 상한은 D-009에서 명시적으로 유지한 계약이므로 이번 리뷰에서 임의로 변경 요구하지 않았다. 실제 PG 미구성, 배송 시뮬레이션, 운영 DB baseline 준비도 이미 문서화된 미완료/제외 범위로 구분했다. 기존 S1~S6의 완료 판정은 그 당시 범위에 대한 것이며 저장소 전체 무결함을 의미하지 않는다.

**판정:** 기존 커머스와 정합성 보완의 기반은 갖춰졌지만, 가격 반영·상품 쓰기 경합·계정 보안·브라우저 주문 경로에 실제 수정할 문제가 남아 있다. 해당 문제를 보완한 뒤 예정된 재고 확장 S1로 진행하는 순서가 적절하다.

**미확인·위험:** 실제 운영 환경과 외부 결제·전체 Kafka 플로우는 검증하지 않았다. 제품 수정은 수행하지 않아 위 8개 문제는 현재 코드에 남아 있다.
