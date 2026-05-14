# Multi-stage build for notification-service
FROM gradle:8-jdk21-alpine AS build

WORKDIR /app

# Copy all project files
COPY . .

# Build only the notification-service module
RUN gradle :notification-service:bootJar --no-daemon

# Runtime stage
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copy the built jar from build stage
COPY --from=build /app/notification-service/build/libs/*.jar app.jar

# Expose port
EXPOSE 8084

ENTRYPOINT ["java", "-Dspring.profiles.active=docker", "-jar", "app.jar"]
