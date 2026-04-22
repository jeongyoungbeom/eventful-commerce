# Multi-stage build for payment-service
FROM gradle:8-jdk21-alpine AS build

WORKDIR /app

# Copy all project files
COPY . .

# Build only the payment-service module
RUN gradle :payment-service:bootJar --no-daemon

# Runtime stage
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copy the built jar from build stage
COPY --from=build /app/payment-service/build/libs/*.jar app.jar

# Expose port
EXPOSE 8082

# Run with docker profile
ENTRYPOINT ["java", "-Dspring.profiles.active=docker", "-jar", "app.jar"]
