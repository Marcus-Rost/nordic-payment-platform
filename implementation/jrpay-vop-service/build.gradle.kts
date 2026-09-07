plugins {
    id("io.spring.dependency-management")
    id("org.springframework.boot")
}

dependencyManagement {
    imports {
        mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
    }
}

dependencies {
    implementation(project(":jrpay-domain-model"))
    implementation(project(":jrpay-security-starter"))

    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Caffeine cache
    implementation("com.github.ben-manes.caffeine:caffeine")

    // Apache Commons Text for Jaro-Winkler
    implementation("org.apache.commons:commons-text:1.12.0")

    // Resilience4j circuit breaker
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")
    implementation("org.springframework.boot:spring-boot-starter-aop")

    // Bucket4j rate limiting — added when rate limiting layer is implemented
    // implementation("com.bucket4j:bucket4j_jvm-core:8.14.0")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("net.jqwik:jqwik:1.9.2")
}
