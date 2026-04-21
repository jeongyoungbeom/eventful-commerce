# 개발 인프라 관리 가이드

## 📦 포함된 서비스

- **PostgreSQL 16** - 주문/결제/배송 서비스용 데이터베이스
- **Redis Cluster** - 6노드 (3 마스터 + 3 레플리카) 재고 관리
- **Kafka** - 이벤트 스트리밍 (KRaft 모드)

---

## 🚀 빠른 시작

### 1. 전체 인프라 시작

```bash
./start-dev.sh
```

### 2. 상태 확인

```bash
./status-dev.sh
```

### 3. 전체 인프라 중지

```bash
./stop-dev.sh
```

---

## 📋 상세 사용법

### 개별 서비스 제어

```bash
# 특정 서비스만 재시작
docker-compose -f docker-compose-dev.yml restart postgres
docker-compose -f docker-compose-dev.yml restart kafka

# 로그 확인
docker-compose -f docker-compose-dev.yml logs -f postgres
docker-compose -f docker-compose-dev.yml logs -f redis-node-1
docker-compose -f docker-compose-dev.yml logs -f kafka

# 특정 서비스만 중지
docker-compose -f docker-compose-dev.yml stop postgres
```

---

## 🔍 서비스 접속 정보

### PostgreSQL
```
Host: localhost
Port: 5432
User: postgres
Password: postgres
Databases: order_service, payment_service, shipping_service
```

**접속 테스트:**
```bash
docker exec -it eventful-postgres psql -U postgres -c "SELECT version();"
```

### Redis Cluster
```
Ports: 7001-7006
Nodes: 6개 (3 마스터 + 3 레플리카)
```

**클러스터 상태 확인:**
```bash
docker exec -it redis-node-1 redis-cli -p 7001 cluster nodes
docker exec -it redis-node-1 redis-cli -p 7001 cluster info
```

**데이터 확인:**
```bash
# Hash Tag 키 조회
docker exec -it redis-node-1 redis-cli -c -p 7001 get "{inventory}:stock:default"
docker exec -it redis-node-1 redis-cli -c -p 7001 keys "{inventory}:*"
```

### Kafka
```
Bootstrap Server: localhost:9092
```

**토픽 확인:**
```bash
docker exec -it eventful-kafka kafka-topics.sh --bootstrap-server localhost:9092 --list
```

**토픽 생성:**
```bash
docker exec -it eventful-kafka kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --create --topic test-topic \
  --partitions 3 \
  --replication-factor 1
```

---

## 🧹 데이터 초기화

### 전체 데이터 삭제 (조심!)
```bash
docker-compose -f docker-compose-dev.yml down -v
```

### 특정 서비스만 초기화
```bash
# PostgreSQL 데이터 삭제
docker-compose -f docker-compose-dev.yml stop postgres
docker volume rm sideproject_postgres_data
docker-compose -f docker-compose-dev.yml up -d postgres

# Redis Cluster 재초기화
docker-compose -f docker-compose-dev.yml stop redis-node-1 redis-node-2 redis-node-3 redis-node-4 redis-node-5 redis-node-6
docker volume rm sideproject_redis-node-{1..6}-data
docker-compose -f docker-compose-dev.yml up -d redis-node-1 redis-node-2 redis-node-3 redis-node-4 redis-node-5 redis-node-6
sleep 5
docker exec -it redis-node-1 redis-cli --cluster create \
  redis-node-1:7001 redis-node-2:7002 redis-node-3:7003 \
  redis-node-4:7004 redis-node-5:7005 redis-node-6:7006 \
  --cluster-replicas 1 --cluster-yes
```

---

## 🐛 트러블슈팅

### 포트 충돌
```bash
# 포트 사용 중인 프로세스 확인
netstat -tuln | grep 5432
netstat -tuln | grep 7001
netstat -tuln | grep 9092

# 기존 컨테이너 정리
docker ps -a | grep eventful
docker rm -f $(docker ps -aq -f name=eventful)
docker rm -f $(docker ps -aq -f name=redis-node)
```

### Redis Cluster 초기화 실패
```bash
# 노드 정리 후 재시도
docker-compose -f docker-compose-dev.yml down -v
docker-compose -f docker-compose-dev.yml up -d
sleep 10
docker exec -it redis-node-1 redis-cli --cluster create \
  redis-node-1:7001 redis-node-2:7002 redis-node-3:7003 \
  redis-node-4:7004 redis-node-5:7005 redis-node-6:7006 \
  --cluster-replicas 1 --cluster-yes
```

### 컨테이너가 바로 죽는 경우
```bash
# 로그 확인
docker-compose -f docker-compose-dev.yml logs [service-name]

# 예: PostgreSQL 로그
docker-compose -f docker-compose-dev.yml logs postgres
```

---

## 💡 팁

### 자동 실행 (선택사항)
WSL2 시작 시 자동으로 인프라 시작하려면:

```bash
echo 'cd ~/sideProject && ./start-dev.sh' >> ~/.bashrc
```

### 빠른 재시작
```bash
# 데이터 유지하며 재시작
docker-compose -f docker-compose-dev.yml restart

# 완전 재시작 (데이터 삭제)
docker-compose -f docker-compose-dev.yml down -v
./start-dev.sh
```

---

## 📝 참고

- 모든 데이터는 Docker Volume에 저장됨
- `down -v` 명령어 사용 전 백업 필수!
- 개발 중에는 `stop` 사용 (데이터 유지)
- 완전 초기화가 필요할 때만 `down -v` 사용
