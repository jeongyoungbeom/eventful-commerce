# 외부 요청과 클라이언트 IP 계약

외부 요청은 Nginx → Gateway → 내부 서비스로 전달한다. Gateway·내부 서비스의 포트를 외부에 직접 공개하지 않는다. 현재 첫 외부 프록시인 Nginx는 X-Real-IP와 X-Forwarded-For를 실제 연결의 remote_addr로 덮어쓰고 Forwarded를 제거한다. 사용자 서비스의 요청 제한·로그인 제한·감사는 같은 clientIp 함수로 단일 X-Real-IP만 사용한다. 값이 없거나 쉼표가 포함된 체인이면 연결 주소를 사용하며 다른 IP 헤더는 무시한다.

앞에 다른 프록시를 추가할 때는 신뢰할 프록시 주소를 명시하고 실제 클라이언트 주소 복원 계약을 함께 변경한다. 임의 외부 전달 헤더나 가장 왼쪽 주소를 그대로 신뢰하지 않는다.

허용 Origin의 주문 사전 확인에는 Content-Type·Authorization·Idempotency-Key를 허용한다. 주문 생성의 Idempotency-Key 필수 검사는 유지한다.
