plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
}

dependencies {
    implementation(project(":common-auth"))

    // Spring Security & JWT
    implementation("org.springframework.boot:spring-boot-starter-security")
    
    // Validation
    implementation("org.springframework.boot:spring-boot-starter-validation")
    
    // Web & Data
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    
    // Database
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Mail
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")

    // Logging
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.0")
    
    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
