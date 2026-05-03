FROM gradle:8-jdk21-alpine AS build

WORKDIR /app
COPY . .
RUN gradle :product-service:bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
COPY --from=build /app/product-service/build/libs/*.jar app.jar

EXPOSE 8086

ENTRYPOINT ["java", "-Dspring.profiles.active=docker", "-jar", "app.jar"]
