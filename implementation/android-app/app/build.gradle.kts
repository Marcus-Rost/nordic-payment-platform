plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.jrpay.client"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jrpay.client"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // Identity OAuth client + redirect URI; overridable per-build.
        manifestPlaceholders["jrpayOauthRedirectScheme"] = "jrpayclient"
        manifestPlaceholders["jrpayOauthRedirectHost"] = "oauth-callback"

        // Surfaced as BuildConfig.IDENTITY_BASE_URL / API_BASE_URL to the
        // OAuth + API clients. Defaults target the Android emulator's host
        // loopback (10.0.2.2). Override per-flavor for staging / prod.
        buildConfigField("String", "IDENTITY_BASE_URL", "\"http://10.0.2.2:8097\"")
        // API_BASE_URL points at the client-web nginx, NOT bare port 80 (which
        // has nothing). The nginx aggregates /api/v1/customer/** across the
        // backend services (account-ledger, bill-payment, psd2-api,
        // notifications, identity) — same reverse-proxy pattern the web SPA
        // uses, so the Android app and web app share one customer surface.
        buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:5173\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Networking + JSON
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Encrypted storage for tokens
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Biometric prompt
    implementation("androidx.biometric:biometric:1.2.0-alpha05")

    // OAuth2 + Custom Tabs (BankID redirect)
    implementation("androidx.browser:browser:1.8.0")

    // Room — UC-G28 offline cache. Annotation processing via KSP because the
    // codebase is Kotlin — Java's `annotationProcessor` configuration silently
    // skips Kotlin sources, leaving Room_Impl classes unbuilt and the app
    // crashing on first DB access at runtime.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
