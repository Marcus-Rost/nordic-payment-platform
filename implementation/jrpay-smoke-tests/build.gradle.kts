plugins {
    id("io.spring.dependency-management")
}

dependencyManagement {
    imports {
        mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
    }
}

// This module contains only tests — no main source, no bootable app
tasks.named<Jar>("jar") { enabled = false }

dependencies {
    testImplementation(project(":jrpay-domain-model"))

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}
