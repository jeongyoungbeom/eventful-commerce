# 🐳 Docker 컨테이너 환경 가이드

완전 컨테이너화된 Eventful Commerce 백엔드 시스템입니다.

## 📦 포함된 서비스

### **인프라:**
- PostgreSQL 16 (4개 DB: order, payment, shipping, notification)
- Redis Cluster (6노드: 3 마스터 + 3 레플리카)
- Kafka (KRaft 모드)

### **애플리케이션:**
- order-service (8081)
- payment-service (8082)
- shipping-service (8083)

---

## 🚀 빠른 시작

### **1. 전체 시스템 시작**
```bash
chmod +x start-all.sh stop-all.sh
./start-all.sh
```

이 명령어는 자동으로:
1. 모든 컨테이너 시작
2. Redis Cluster 초기화
3. 서비스 상태 확인

### **2. 서비스 확인**
```bash
docker-compose ps
```

### **3. 로그 확인**
```bash
# 모든 서비스 로그
docker-compose logs -f

# 특정 서비스 로그
docker-compose logs -f order-service
docker-compose logs -f payment-service
docker-compose logs -f shipping-service
```

### **4. 전체 시스템 종료**
```bash
./stop-all.sh
```

---

## 🔧 개별 서비스 관리

### **특정 서비스만 재시작**
```bash
docker-compose restart order-service
```

### **특정 서비스만 재빌드**
```bash
docker-compose up -d --build order-service
```

### **서비스 스케일링**
```bash
docker-compose up -d --scale order-service=3
```

---

## 🗄️ 데이터베이스 접속

### **PostgreSQL**
```bash
docker exec -it eventful-postgres psql -U postgres -d order_service

# 또는
psql -h localhost -p 5432 -U postgres -d order_service
```

### **Redis Cluster**
```bash
docker exec -it redis-node-1 redis-cli -c -p 7001

# 클러스터 상태 확인
docker exec -it redis-node-1 redis-cli -p 7001 cluster nodes
docker exec -it redis-node-1 redis-cli -p 7001 cluster info
```

### **Kafka**
```bash
# 토픽 목록
docker exec -it eventful-kafka kafka-topics.sh --bootstrap-server localhost:9092 --list

# 토픽 생성
docker exec -it eventful-kafka kafka-topics.sh --bootstrap-server localhost:9092 --create --topic test-topic --partitions 3 --replication-factor 1

# 메시지 소비
docker exec -it eventful-kafka kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic order-events --from-beginning
```

---

## 🧹 데이터 초기화

### **모든 데이터 삭제 (볼륨 포함)**
```bash
docker-compose down -v
```

### **특정 볼륨만 삭제**
```bash
docker volume rm sideproject_postgres_data
docker volume rm sideproject_redis-node-1-data
```

---

## 🐛 트러블슈팅

### **Redis Cluster가 초기화되지 않는 경우**
```bash
# 수동으로 초기화
./scripts/init-redis-cluster.sh

# 또는 완전 재시작
docker-compose down -v
./start-all.sh
```

### **포트 충돌 문제**
```bash
# 사용 중인 포트 확인
sudo lsof -i :8081
sudo lsof -i :5432
sudo lsof -i :7001

# 프로세스 종료
sudo kill -9 <PID>
```

### **빌드 캐시 문제**
```bash
# 캐시 없이 재빌드
docker-compose build --no-cache

# 또는
docker-compose up -d --build --force-recreate
```

### **디스크 공간 부족**
```bash
# 사용하지 않는 이미지/컨테이너/볼륨 정리
docker system prune -a --volumes
```

---

## 📊 헬스 체크

### **모든 서비스 상태**
```bash
curl http://localhost:8081/actuator/health
curl http://localhost:8082/actuator/health
curl http://localhost:8083/actuator/health
```

### **PostgreSQL**
```bash
docker exec eventful-postgres pg_isready -U postgres
```

### **Kafka**
```bash
docker exec eventful-kafka kafka-broker-api-versions.sh --bootstrap-server localhost:9092
```

---

## 🔄 개발 워크플로우

### **코드 수정 후 재배포**
```bash
# 1. 특정 서비스만 재빌드
docker-compose up -d --build order-service

# 2. 로그 확인
docker-compose logs -f order-service
```

### **IntelliJ vs Docker 선택**

#### **IntelliJ 실행 (로컬 개발):**
- 장점: 빠른 재시작, 디버깅, 핫 리로드
- 사용: `application.yml` (localhost 설정)

#### **Docker 실행 (통합 테스트):**
- 장점: 프로덕션과 동일한 환경, 서비스 간 통신 테스트
- 사용: `application-docker.yml` (컨테이너 이름 설정)

---

## 📝 환경 변수

`docker-compose.yml`에서 환경 변수를 수정할 수 있습니다:

```yaml
services:
  order-service:
    environment:
      - SPRING_PROFILES_ACTIVE=docker
      - JAVA_OPTS=-Xmx512m -Xms256m
      - TZ=Asia/Seoul
```

---

## 🎯 다음 단계

1. **Week 2:** Redisson 분산락 구현
2. **Week 3:** Kafka 이벤트 기반 통신 구현
3. **Week 4:** 부하 테스트 및 모니터링

---

## 📚 참고 자료

- [Docker Compose 공식 문서](https://docs.docker.com/compose/)
- [Redis Cluster 튜토리얼](https://redis.io/docs/management/scaling/)
- [Kafka Docker 가이드](https://kafka.apache.org/documentation/#docker)
