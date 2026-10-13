import java.util.Properties

plugins {
    // AGP 9.0+ 内置 Kotlin 支持：只需 com.android.application
    id("com.android.application")
}

// ---------- 签名配置：从 key.properties 读取 ----------
// 注意：这里必须 import java.util.Properties —— 直接写 java.util.Properties() 会失败，
// 因为 Kotlin DSL 里 `java` 被 Gradle 的 java 扩展遮蔽了。
val keystorePropsFile = rootProject.file("key.properties")
// 显式写类型：否则 Kotlin DSL 会对 Properties().apply{} 产生类型推断递归
val keystoreProps: Properties = Properties()
if (keystorePropsFile.exists()) {
    keystorePropsFile.inputStream().use { stream -> keystoreProps.load(stream) }
    // 防御：若 key.properties 带 UTF-8 BOM，BOM 会粘在第一个键名上
    // （"storeFile" 变成 "\uFEFFstoreFile"），导致 getProperty 返回 null。
    val bomKeyName: String? = keystoreProps.stringPropertyNames()
        .firstOrNull { it.startsWith('\uFEFF') }
    if (bomKeyName != null) {
        val fixedName: String = bomKeyName.substring(1)
        val value: String? = keystoreProps.getProperty(bomKeyName)
        keystoreProps.remove(bomKeyName)
        if (value != null) keystoreProps.setProperty(fixedName, value)
    }
}

android {
    namespace = "com.dafeiyu.pet"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dafeiyu.pet"
        minSdk = 26          // 悬浮窗 + 前台服务在 8.0 起才规范
        targetSdk = 36
        versionCode = 3
        versionName = "1.2"
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (keystorePropsFile.exists())
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
}
