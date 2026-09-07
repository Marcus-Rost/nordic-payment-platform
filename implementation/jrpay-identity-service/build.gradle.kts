plugins {
    id("io.spring.dependency-management")
    id("org.springframework.boot")
}

dependencyManagement {
    imports {
        mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
    }
    dependencies {
        // Spring Boot 3.4's BOM pins testcontainers 1.20.6 which speaks an
        // older Docker Engine API than what's required by Docker ≥ 29.
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

    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")

    // Spring Authorization Server (RFC 6749 + RFC 7636 PKCE + RFC 7009 + RFC 7662 + OIDC)
    implementation("org.springframework.security:spring-security-oauth2-authorization-server:1.4.1")

    // Resource server — for /sca/* endpoints that gate on the identity-service-issued token
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    // PostgreSQL + Flyway
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // TOTP (RFC 6238) — used by the SCA TOTP factor
    implementation("dev.samstevens.totp:totp:1.7.1")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    // Testcontainers 1.21.3+ negotiates against Docker Engine ≥ 29 (API
    // 1.44+); Spring Boot 3.4's BOM pins 1.20.6 which breaks against
    // newer engines.
    testImplementation("org.testcontainers:junit-jupiter:1.21.3")
    testImplementation("org.testcontainers:postgresql:1.21.3")
    testImplementation("com.github.docker-java:docker-java-api:3.5.0")
    testImplementation("com.github.docker-java:docker-java-transport-zerodep:3.5.0")
    testRuntimeOnly("com.h2database:h2")
}

// docker-java 3.5 still defaults to API version 1.32 in `BaseCmdImpl`, which
// Docker Engine ≥ 29 rejects (min API 1.44). Force a modern version via env
// — docker-java honours DOCKER_API_VERSION when constructing the client.
tasks.withType<Test> {
    environment("DOCKER_API_VERSION", "1.48")
}
