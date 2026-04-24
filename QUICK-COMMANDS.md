# 🚀 빠른 명령어 참조

## 📦 개발자용 (빌드 & 푸시)

```bash
# Docker Hub 로그인
docker login

# 빌드 & 푸시
./build-and-push.sh

# 특정 서비스만 빌드 & 푸시
docker build -f order-service/Dockerfile -t jybeomss1/order-service:latest .
docker push jybeomss1/order-service:latest
```

---

## 🌐 배포용 (Pull & 실행)

```bash
# 전체 시스템 시작
./start-all.sh

# 또는
./pull-and-start.sh

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
./start-all.sh
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
./start-all.sh
```
