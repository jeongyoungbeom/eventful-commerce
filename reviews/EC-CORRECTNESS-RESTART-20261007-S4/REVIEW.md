# 4단계 배송 취소 독립 리뷰

Task ID: EC-CORRECTNESS-RESTART-20261007-S4
검토일: 2026-10-19 (Asia/Seoul)
수행 역할: workflow-independent-review
판정: finding 0개 / 4단계 completed

## 검토 범위

S3 이후 S4에서 변경한 24개 파일과 추가한 7개 파일을 검토했다. 주문 취소 요청·승인 처리·재고 복구, 배송 생성·출고·취소·완료·실패, 이벤트 소비자, API/조회 응답, 복구 scheduler, DB 제약과 관련 테스트가 대상이다. 직접 영향받는 Orders·사가·Outbox·멱등 marker와 호출 경로도 대조했다. 제품 코드와 테스트는 수정하지 않았다.

## 요구사항 출처

사용자의 `/home/jybeomss/sideProject` 개발 계속 요청, 저장소 AGENTS.md, active TASK의 S4 완료 조건 1~6, COMMERCE_REMEDIATION_PLAN.md의 4단계, global D-001~D-004 및 S4 D-008을 적용했다. TASK와 ID가 일치하는 개발 HANDOFF는 실제 코드·검증 결과와 대조해 인수했다. 리뷰 스킬은 `/home/jybeomss/sideProject/.agents/skills/workflow-independent-review/SKILL.md`다.

## 수행한 검증

- 현재 소스/설정 340개 파일의 SHA-256이 개발 검증의 source-manifest.json과 모두 일치함을 확인했다. S3 기준선 대비 S4 변경 24개·추가 7개를 범위로 삼았다.
- 보존된 실제 build.log의 `BUILD SUCCESSFUL in 41s`와 XML을 직접 확인했다. 주문 108개·배송 4개, 합계 112개이며 실패·오류·생략은 모두 0개다. 실제 PostgreSQL·Redis 통합 56개와 API 상태 테스트 5개가 포함된다.
- 동일 코드의 성공 검증을 반복하지 않는 저장소 규칙에 따라 이번 리뷰에서는 테스트·빌드를 재실행하지 않았다. 위 112개는 이전 개발 세션의 실행 결과이며 이번 세션의 새 실행 수가 아니다.
- `git diff --check`를 이번 리뷰에서 실행해 통과했다. 제품 소스·테스트의 hash가 리뷰 종료 시에도 같은지 확인한다.

| 확인한 실패 조건 | 코드와 기존 검증 대조 결과 |
|---|---|
| 배송 승인 전에 환불·판매 재고가 복원됨 | 확정 주문은 PENDING과 요청 Outbox만 기록한다. 일치하는 승인 응답을 처리할 때만 cancelAndRecord를 실행한다. 예약 취소와 미발행 확정 전 재고 보상은 별도 경로다. |
| 출고와 취소가 동시에 성공함 | 생성/취소는 seller_order_id UNIQUE·ON CONFLICT 이후 같은 행 잠금을 사용한다. 출고/완료/실패도 같은 행 잠금을 얻는다. 출고 우선이면 거절, 취소 우선이면 출고를 차단한다. 양방향 DB Lock 대기와 반복 경합 검증이 있다. |
| 취소 후 늦은 확정이 배송을 재생성함 | CANCELED 배송 행을 영속 보존하고 중복 확정은 기존 행의 상태를 덮어쓰지 않는다. 생성 전 취소·생성/취소 경합·늦은 worker를 검증했다. |
| 중복·반대 응답으로 환불/재고 효과가 반복됨 | 주문·구매자·판매자 주문·requestId를 대조하고 최초 결정 이후 상반된 응답은 거부한다. marker·DB·Outbox는 로컬 트랜잭션에 참여한다. Redis 복원 후 DB 롤백은 멱등 restock과 Outbox 재기록으로 복구한다. |
| 응답 유실·재기동 뒤 대기가 사라짐 | PENDING·최초 requestId·사유·시각을 저장하고 scheduler는 같은 requestId로 새 Outbox를 기록한다. 배송은 최초 결정을 재응답한다. 별도 커밋 트랜잭션·신규 scheduler 인스턴스 검증이 있다. |
| 일부 판매자의 출고가 전체 환불로 이어짐 | 판매자별 승인/거절을 보존하고 승인 판매자만 ORDER_CANCELED에 포함한다. API는 PENDING 202·REJECTED 409·잠금 실패 503·취소 200을 구분하고 조회에 판매자별 상태를 노출한다. |
| 출고 후 배송 실패가 단순 보상으로 처리됨 | shippedAt과 배송 상태를 근거로 거절한다. 출고 전 실패는 배송 승인 이후에만 환불 사가를 시작한다. STARTED/COMPLETED/출고 후 FAILED 및 출고 전 실패 통합 검증을 대조했다. |

## 검토 제외 범위

5~6단계의 정산 배치·웹훅 보완, ARL, 기존 DB 자료 이전, 반품 수령·검수, 배포는 제외했다. 기존 S1~S3 변경·리뷰·근거를 보존했다. 이 리뷰는 전체 프로젝트의 결함 부재를 보증하는 검토가 아니다.

## 미검증 항목

실제 Kafka 브로커 전달·DLT, 외부 PG, OS 프로세스의 실제 종료/재기동, 실제 비동기 완료 worker 실행은 추가 검증하지 않았다. 기존 통합 검증은 배송/주문 소비자를 직접 호출하고, 재기동은 신규 scheduler 인스턴스와 별도 커밋 트랜잭션으로 대체하며, 완료 worker는 mock을 사용한다. 실제 배송 완료·실패 서비스와 Spring 트랜잭션 프록시·DB 잠금은 기존 검증에 포함된다.

## Finding

없음. 검토 범위에서 재현 가능하거나 코드 흐름으로 입증되는 수정 필요 결함을 발견하지 않았다.

## 종료와 다음 작업

S4 TASK를 completed, 인수한 HANDOFF를 inactive로 전환한다. finding이 없으므로 RESOLUTION 및 workflow-apply-review는 필요 없다. 독립 리뷰 스킬의 “finding이 0개이고 … TASK를 `completed`로 바꾸고 종료한다”와 저장소의 한 세션 한 workflow 역할 규칙에 따라 이 세션은 리뷰로 종료한다. 다음 개발 세션은 5단계 정산 배치 누락 방지를 진행한다. 5~6단계는 미착수다.
