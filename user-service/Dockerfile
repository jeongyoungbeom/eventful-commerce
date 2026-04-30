FROM gradle:8.5-jdk21 AS build
WORKDIR /app

# Gradle 캐시를 위한 파일들 먼저 복사
COPY settings.gradle.kts build.gradle.kts ./
COPY common-outbox/build.gradle.kts common-outbox/
COPY common-idempotency/build.gradle.kts common-idempotency/
COPY user-service/build.gradle.kts user-service/

# 의존성 다운로드
RUN gradle dependencies --no-daemon || true

# 전체 소스 복사
COPY . .

# user-service 빌드
RUN gradle :user-service:bootJar --no-daemon

# 실행 이미지
FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /app/user-service/build/libs/*.jar app.jar

EXPOSE 8085

ENTRYPOINT ["java", "-jar", "app.jar"]
