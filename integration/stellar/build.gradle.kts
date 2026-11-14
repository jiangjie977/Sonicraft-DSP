plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.jiangjie.Sonicraft.integration.stellar"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }

    buildFeatures {
        // Stellar API 依赖 AIDL（com.stellar.server.*）
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core:common"))
    // StellarProvider 使用 androidx.core.net.toUri；Stellar API 使用 androidx.annotation
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.annotation)
    implementation(libs.kotlinx.coroutines.android)

    // PrivilegedClient 使用 Hilt 注解
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
