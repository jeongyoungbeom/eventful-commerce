# 🐳 Docker Hub 배포 가이드

Docker Hub를 사용한 이미지 빌드, 푸시, 배포 전체 워크플로우입니다.

## 📦 Docker Hub 정보

**Username:** `jybeomss1`

**이미지:** `jybeomss1/<서비스명>:latest` 형식의 9개 이미지입니다.

`api-gateway`, `order-service`, `payment-service`, `shipping-service`, `notification-service`, `user-service`, `product-service`, `settlement-service`, `inventory-service`를 전체/단일 스크립트에서 모두 지원합니다.

---

## 🚀 빠른 시작

### **처음 사용 (개발자 - 이미지 빌드 & 푸시)**

```bash
# 1. Docker Hub 로그인
docker login

# 2. 빌드 & 푸시
chmod +x build-and-push.sh
./build-and-push.sh
```

### **팀원/배포 환경 (이미지 Pull & 실행)**

```bash
# 1. 최신 이미지 Pull & 실행
chmod +x pull-and-start.sh
./pull-and-start.sh

# 단일 서비스 업데이트 (전체 최초 기동 후)
./pull-and-restart-one.sh inventory-service
```

---

## 📋 전체 워크플로우

### **1️⃣ 개발자: 코드 수정 → 빌드 → 푸시**

```bash
# 코드 수정 후
vim order-service/src/main/kotlin/...

# 빌드 & Docker Hub 푸시
./build-and-push.sh
```

이 스크립트는 자동으로:
1. ✅ Docker Hub 로그인 확인
2. ✅ 재고 서비스를 포함한 9개 서비스 빌드
3. ✅ Docker Hub에 푸시

**소요 시간:** 약 5-10분 (첫 빌드)

---

### **2️⃣ 팀원/서버: Pull → 실행**

```bash
# 최신 이미지 가져오기 & 실행
./pull-and-start.sh
```

이 스크립트는 자동으로:
1. ✅ Docker Hub에서 최신 이미지 pull
2. ✅ 모든 컨테이너 시작
3. ✅ Redis Cluster 초기화

**소요 시간:** 약 2-3분

---

## 🔄 개발 사이클

### **시나리오 A: 코드 수정 → 재배포**

```bash
# 1. 코드 수정
vim order-service/src/main/kotlin/...

# 2. 빌드 & 푸시
./build-and-push.sh

# 3. 로컬에서 테스트
docker-compose down
./pull-and-start.sh

# 4. 팀원들에게 알림
# "order-service 업데이트했습니다! pull-and-start.sh 실행하세요"
```

### **시나리오 B: 특정 서비스만 재배포**

```bash
# order-service만 재빌드 & 푸시
docker build -f order-service/Dockerfile -t jybeomss1/order-service:latest .
docker push jybeomss1/order-service:latest

# 해당 서비스만 재시작
docker-compose pull order-service
docker-compose up -d order-service
```

---

## 🏷️ 버전 태깅 전략

### **Latest 태그 (기본)**
```bash
# 항상 최신 버전 (개발용)
docker tag jybeomss1/order-service:latest jybeomss1/order-service:latest
docker push jybeomss1/order-service:latest
```

### **버전 태그 (프로덕션용)**
```bash
# 특정 버전 태그
docker build -f order-service/Dockerfile -t jybeomss1/order-service:v1.0.0 .
docker push jybeomss1/order-service:v1.0.0

# latest도 함께 푸시
docker tag jybeomss1/order-service:v1.0.0 jybeomss1/order-service:latest
docker push jybeomss1/order-service:latest
```

### **docker-compose.yml에서 버전 지정**
```yaml
services:
  order-service:
    image: jybeomss1/order-service:v1.0.0  # 특정 버전 고정
```

---

## 🧪 통합 테스트 시나리오

### **로컬 환경에서 전체 테스트**

```bash
# 1. 최신 이미지로 실행
./pull-and-start.sh

# 2. 헬스체크
curl http://localhost:8081/actuator/health
curl http://localhost:8082/actuator/health
curl http://localhost:8083/actuator/health

# 3. 로그 확인
docker-compose logs -f order-service
docker-compose logs -f payment-service
docker-compose logs -f shipping-service

# 4. Redis Cluster 확인
docker exec -it redis-node-1 redis-cli -p 7001 cluster nodes

# 5. Kafka 토픽 확인
docker exec -it eventful-kafka kafka-topics.sh --bootstrap-server localhost:9092 --list
```

### **API 엔드포인트 테스트**

```bash
# 주문 생성 (order-service)
curl -X POST http://localhost:8081/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "user-123",
    "items": [{"productId": "prod-1", "quantity": 2}]
  }'

# 결제 처리 (payment-service)
curl -X POST http://localhost:8082/api/payments \
  -H "Content-Type: application/json" \
  -d '{
    "orderId": "order-123",
    "amount": 50000
  }'

# 배송 조회 (shipping-service)
curl http://localhost:8083/api/shipments/order-123
```

---

## 🐛 트러블슈팅

### **1. Docker Hub 푸시 실패**
```bash
# 문제: "denied: requested access to the resource is denied"
# 해결: 로그인 다시 시도
docker logout
docker login
```

### **2. 이미지 크기가 너무 큼**
```bash
# 현재 이미지 크기 확인
docker images | grep jybeomss1

# 해결책: Multi-stage build 이미 적용됨
# Dockerfile에서 alpine 이미지 사용 중
```

### **3. 특정 서비스만 업데이트하고 싶은 경우**
```bash
# order-service만 재빌드
docker build -f order-service/Dockerfile -t jybeomss1/order-service:latest .
docker push jybeomss1/order-service:latest

# order-service만 재시작
docker-compose pull order-service
docker-compose up -d order-service
```

### **4. 캐시 문제로 빌드가 안 되는 경우**
```bash
# 캐시 없이 빌드
docker build --no-cache -f order-service/Dockerfile -t jybeomss1/order-service:latest .
```

---

## 📊 모니터링 & 로그

### **컨테이너 상태 확인**
```bash
docker-compose ps
docker stats
```

### **실시간 로그**
```bash
# 모든 서비스
docker-compose logs -f

# 특정 서비스
docker-compose logs -f order-service
docker-compose logs -f payment-service
docker-compose logs -f shipping-service
```

### **로그 파일로 저장**
```bash
docker-compose logs > logs/full-logs.txt
docker-compose logs order-service > logs/order-service.txt
```

---

## 🔐 보안 고려사항

### **민감 정보 관리**
```bash
# .env 파일 사용 (절대 Git에 커밋 X)
echo "DB_PASSWORD=your-secure-password" > .env

# docker-compose.yml에서 참조
environment:
  - POSTGRES_PASSWORD=${DB_PASSWORD}
```

### **Private 저장소 사용**
```bash
# Docker Hub에서 저장소를 Private으로 설정
# Settings → Make Private
```

---

## 📈 CI/CD 파이프라인 (향후 확장)

### **GitHub Actions 예시**
```yaml
# .github/workflows/docker-build.yml
name: Build and Push to Docker Hub

on:
  push:
    branches: [ main ]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      
      - name: Login to Docker Hub
        uses: docker/login-action@v1
        with:
          username: ${{ secrets.DOCKER_USERNAME }}
          password: ${{ secrets.DOCKER_PASSWORD }}
      
      - name: Build and push
        run: ./build-and-push.sh
```

---

## 🎯 베스트 프랙티스

1. ✅ **코드 변경 → 빌드 → 푸시 → 팀 알림**
2. ✅ **버전 태그 사용 (v1.0.0, v1.1.0 등)**
3. ✅ **이미지 크기 최소화 (alpine 사용)**
4. ✅ **민감 정보는 .env 사용**
5. ✅ **로그 정기적으로 확인**

---

## 🌐 유용한 링크

- [Docker Hub 대시보드](https://hub.docker.com/)
- [Docker 공식 문서](https://docs.docker.com/)
- [Docker Compose 가이드](https://docs.docker.com/compose/)
- [Multi-stage Builds](https://docs.docker.com/build/building/multi-stage/)

---

## 📞 문제 발생 시

1. 로그 확인: `docker-compose logs -f [service-name]`
2. 컨테이너 재시작: `docker-compose restart [service-name]`
3. 완전 재시작: `docker-compose down && ./pull-and-start.sh`
4. 데이터 초기화: `docker-compose down -v && ./pull-and-start.sh`

## 재고 포함 한 번에 실행

최초 1회 `docker login`을 하고 `.env`의 `GMAIL_USERNAME`, `GMAIL_APP_PASSWORD`를 설정합니다. 기존 파일의 값을 유지하며, 누락/빈 `JWT_SECRET`과 `INVENTORY_SERVICE_TOKEN`만 자동 생성합니다. 두 서비스는 동일한 내부 토큰을 사용합니다. 토큰은 출력하지 않으며 `.env`는 Docker 빌드 컨텍스트에서 제외합니다.

```bash
# 개발 PC에서 현재 소스 전체를 빌드·푸시한 뒤 전체 실행
bash ./build-and-push.sh && bash ./pull-and-start.sh

# 서버에서는 최신 이미지 전체를 받아 실행
bash ./pull-and-start.sh

# 재고 서비스만 빌드·푸시 / 최초 전체 기동 이후 개별 교체
bash ./build-and-push-one.sh inventory-service
bash ./pull-and-restart-one.sh inventory-service
```

전체 실행은 9개 이미지를 받은 뒤 PostgreSQL/Kafka/Redis를 시작하고 PostgreSQL TCP 준비를 확인합니다. 기존 볼륨에 `inventory_service` DB가 없으면 이것만 생성하고 Redis 클러스터를 준비한 다음 전체 앱을 기동합니다. 주문·재고 이미지도 Docker Hub의 같은 태그를 사용하며 pull 경로에서 로컬 빌드를 실행하지 않습니다. 앱 기동 완료는 `docker compose ps`와 서비스 로그에서 확인합니다.

기존 DB·볼륨을 삭제하거나 Flyway checksum을 자동 복구하지 않습니다. 기존 재고 DB에 과거 초기 스키마가 남아 있거나 다른 서비스에 미반영 SQL이 있다면 [스키마 정책](docs/schema-migration-policy.md)에 따라 별도 적용해야 합니다. 이 스크립트는 스키마 이전 도구가 아닙니다.

관리자 화면: `http://localhost/api/inventory/admin/index.html` (ADMIN access JWT 필요).
