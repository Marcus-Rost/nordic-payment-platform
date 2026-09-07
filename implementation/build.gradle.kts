plugins {
    java
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("org.springframework.boot") version "3.4.4" apply false
}

allprojects {
    group = "com.jrpay"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")

    java {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-serial"))
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        jvmArgs("-XX:+ExitOnOutOfMemoryError")
    }
}
