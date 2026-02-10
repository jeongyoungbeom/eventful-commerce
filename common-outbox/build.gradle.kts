plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
    id("io.spring.dependency-management")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:3.2.5")
    }
}

dependencies {
    implementation("org.springframework.kafka:spring-kafka")
    compileOnly("jakarta.persistence:jakarta.persistence-api:3.1.0")
    compileOnly("org.springframework.data:spring-data-commons")
    compileOnly("org.springframework.data:spring-data-jpa")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    implementation("io.github.oshai:kotlin-logging-jvm:7.0.0")
}
