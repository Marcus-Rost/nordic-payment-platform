plugins {
    id("io.spring.dependency-management")
    id("org.springframework.boot")
}

dependencyManagement {
    imports {
        mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
    }
    dependencies {
        // Force Testcontainers ≥ 1.21.3 — Spring Boot 3.4's BOM pin (1.20.6)
        // can't negotiate against Docker Engine ≥ 29.
        dependency("org.testcontainers:testcontainers:1.21.3")
        dependency("org.testcontainers:junit-jupiter:1.21.3")
        dependency("org.testcontainers:postgresql:1.21.3")
        dependency("org.testcontainers:jdbc:1.21.3")
        dependency("org.testcontainers:database-commons:1.21.3")
    }
}

dependencies {
    implementation(project(":jrpay-domain-model"))
    implementation(project(":jrpay-security-starter"))
    implementation(project(":jrpay-security-starter-kafka"))

    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    // Kafka
    implementation("org.springframework.kafka:spring-kafka")

    // PostgreSQL + Flyway
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // JSONB mapping
    implementation("io.hypersistence:hypersistence-utils-hibernate-63:3.9.0")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.testcontainers:junit-jupiter:1.21.3")
    testImplementation("org.testcontainers:postgresql:1.21.3")
    testImplementation("com.github.docker-java:docker-java-api:3.5.0")
    testImplementation("com.github.docker-java:docker-java-transport-zerodep:3.5.0")
}

tasks.withType<Test> {
    environment("DOCKER_API_VERSION", "1.48")
}
