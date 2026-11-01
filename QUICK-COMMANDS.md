# 🚀 빠른 명령어 참조

## 📦 개발자용 (빌드 & 푸시)

```bash
# Docker Hub 로그인
docker login

# 빌드 & 푸시
./build-and-push.sh

# 재고 서비스만 빌드 & 푸시
bash ./build-and-push-one.sh inventory-service

# 전체 9개 이미지 빌드·푸시 후 한 번에 실행
bash ./build-and-push.sh && bash ./pull-and-start.sh
```

---

## 🌐 배포용 (Pull & 실행)

```bash
# 재고를 포함한 전체 9개 서비스 pull & 실행
bash ./pull-and-start.sh

# 최초 전체 기동 이후 재고만 업데이트
bash ./pull-and-restart-one.sh inventory-service

# 전체 시스템 종료
./stop-all.sh
```

---

## 🔍 상태 확인

```bash
# 컨테이너 상태
docker-compose ps

# 헬스체크
curl http://localhost:8081/actuator/health  # order
curl http://localhost:8082/actuator/health  # payment
curl http://localhost:8083/actuator/health  # shipping

# Redis Cluster
docker exec -it redis-node-1 redis-cli -p 7001 cluster nodes

# Kafka 토픽
docker exec -it eventful-kafka kafka-topics.sh --bootstrap-server localhost:9092 --list
```

---

## 📋 로그 확인

```bash
# 실시간 로그 (전체)
docker-compose logs -f

# 특정 서비스
docker-compose logs -f order-service
docker-compose logs -f payment-service
docker-compose logs -f shipping-service
```

---

## 🔄 재시작

```bash
# 특정 서비스 재시작
docker-compose restart order-service

# 특정 서비스만 업데이트
docker-compose pull order-service
docker-compose up -d order-service

# 전체 재시작
docker-compose down
./pull-and-start.sh
```

---

## 🧹 정리

```bash
# 컨테이너만 종료
docker-compose down

# 컨테이너 + 볼륨 삭제
docker-compose down -v

# 전체 클린업
docker system prune -a --volumes
```

---

## 🐛 문제 해결

```bash
# Redis Cluster 수동 초기화
./scripts/init-redis-cluster.sh

# 캐시 없이 재빌드
docker-compose build --no-cache

# 완전 초기화
docker-compose down -v
docker system prune -a --volumes
./pull-and-start.sh
```

최초 `docker login`과 `.env`의 Gmail 설정이 필요합니다. 전체 실행은 누락된 JWT/재고 내부 토큰과 `inventory_service` DB를 준비합니다. 기존 DB·볼륨은 유지하며 스키마 이전은 자동 수행하지 않습니다. 재고 화면은 `http://localhost/api/inventory/admin/index.html`입니다.
