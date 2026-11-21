import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// U8q：release 深度混淆 + 专属签名。密钥参数在根目录 keystore.properties（.gitignore 已排除），
// jks 本体在 keystore/sonicraft-release.jks（同样不入库）——丢失无法恢复已发版本，务必备份。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.jiangjie.Sonicraft"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jiangjie.Sonicraft"
        minSdk = 29
        // 35 → 36（Android 16）。国内商店与 Play 均按年度要求跟进最新 targetSdk；
        // compileSdk 早已是 36，升 target 只是启用 A16 的 targetSdk 门控行为。
        targetSdk = 36
        versionCode = 9
        versionName = "0.4.0"
    }

    buildFeatures {
        compose = true
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // U8q：R8 深度混淆（类名/方法名重写 + 资源收缩），规则见 proguard-rules.pro
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        jniLibs {
            // Android 15 起 16KB 页大小为硬性要求，禁止旧式打包（会剥离对齐段）
            useLegacyPackaging = false
        }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:audio"))
    implementation(project(":core:engine"))
    implementation(project(":integration:stellar"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.kotlinx.coroutines.android)

    // zip 导入：Android 自带 ZipFile 对 GBK 文件名（无 EFS 标志）解码行为不一致，
    // zip4j 按 central directory 原始字节 + 指定 charset 解码，正确支持 Windows 中文打包
    implementation("net.lingala.zip4j:zip4j:2.11.5")

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.compose.bom))
}
