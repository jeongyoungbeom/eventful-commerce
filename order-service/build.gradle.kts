plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
}

dependencies {
    implementation(project(":common-outbox"))
    implementation(project(":common-idempotency"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    runtimeOnly("org.postgresql:postgresql")

    implementation("org.springframework.kafka:spring-kafka")

//    implementation("org.flywaydb:flyway-core")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:kafka")

    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("org.mockito:mockito-inline:5.2.0")

    implementation("io.github.oshai:kotlin-logging-jvm:7.0.0")
}
