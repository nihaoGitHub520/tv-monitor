import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.tvmonitor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tvmonitor"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // TV 启动器（leanback）必需；无 touchscreen 要求
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        // 固定 debug 签名：keystore 入库，保证 CI 每次构建签名一致，可直接覆盖安装
        // （否则 CI runner 随机生成 keystore，各包签名不同，升级必报 INSTALL_FAILED_UPDATE_INCOMPATIBLE）
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }

    lint {
        // 编译期 lint 不阻塞交付（CI 侧另行配置）
        abortOnError = false
    }

    testOptions {
        // CI 日志输出完整堆栈，便于定位失败测试（2026-10-25 OnvifSoapTest 14 项失败排查经验）
        unitTests.all {
            it.testLogging.exceptionFormat = TestExceptionFormat.FULL
        }
    }
}

dependencies {
    // ---- AndroidX UI ----
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // ---- Kotlin 生态 ----
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // ---- 网络（ONVIF SOAP / m3u / 连通性测试） ----
    implementation(libs.okhttp)

    // ---- media3 ExoPlayer：Google Maven 无 BOM 构件，单体库显式统一版本 ----
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.rtsp)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource.okhttp)

    // ---- 测试（QA 负责编写） ----
    testImplementation(libs.junit)
    // Robolectric：为 JVM 单测提供真实 Android 实现（org.xmlpull.v1 / android.util.Base64 等）。
    // 背景：android.jar 在 JVM 单测中方法体均为 Stub，XmlPullParserFactory.newInstance() 抛
    // RuntimeException("Stub!")，导致 OnvifSoapTest 14 项解析用例失败（2026-10-25 CI 实录）。
    testImplementation(libs.robolectric)
}
