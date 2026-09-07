// Multi-project Gradle build for the Android codegen smoke.
//
// Each generated module under `generated/<spec>/` is an independent Kotlin
// subproject containing Retrofit interfaces + Moshi data classes + enums
// produced by openapi-generator. The root build applies a common Kotlin +
// dependency setup to every subproject so the smoke gate is a single
// `gradle compileKotlin` invocation.
//
// Verification target: every generated module compiles cleanly against
// the same dependency set a real Android app ships with.

plugins {
    kotlin("jvm") version "2.0.21" apply false
}

allprojects {
    repositories {
        mavenCentral()
    }
}

subprojects {
    plugins.apply("org.jetbrains.kotlin.jvm")

    configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(21)
    }

    dependencies {
        val implementation by configurations

        // Retrofit + OkHttp — the runtime backing the generated
        // `jvm-retrofit2` API interfaces.
        implementation("com.squareup.retrofit2:retrofit:2.11.0")
        implementation("com.squareup.retrofit2:converter-scalars:2.11.0")
        implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
        implementation("com.squareup.okhttp3:okhttp:4.12.0")

        // Moshi — JSON codec used by openapi-generator's moshi output.
        // Includes adapters for kotlin nullability + java.time types.
        implementation("com.squareup.moshi:moshi:1.15.1")
        implementation("com.squareup.moshi:moshi-kotlin:1.15.1")
        implementation("com.squareup.moshi:moshi-adapters:1.15.1")

        // Coroutines — `useCoroutines=true` emits `suspend` functions.
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    }
}
