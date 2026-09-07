plugins {
    `java-library`
    id("io.spring.dependency-management")
    id("org.springframework.boot") apply false
}

dependencyManagement {
    imports {
        mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
    }
}

dependencies {
    api(project(":jrpay-domain-model"))

    // Spring Boot auto-configuration
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Structured JSON logging
    implementation("net.logstash.logback:logstash-logback-encoder:8.0")

    // Micrometer metrics + tracing
    implementation("io.micrometer:micrometer-core")
    implementation("io.micrometer:micrometer-tracing")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")

    // Annotation processor for auto-configuration metadata
    annotationProcessor("org.springframework.boot:spring-boot-autoconfigure-processor")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
}

// This is a library, not a bootable application
tasks.named<Jar>("jar") {
    enabled = true
}
