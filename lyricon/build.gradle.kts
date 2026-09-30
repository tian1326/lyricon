import com.android.build.api.dsl.ApplicationExtension
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    id("com.mikepenz.aboutlibraries.plugin.android")
}

configure<ApplicationExtension> {
    namespace = rootProject.extra["appPackageName"] as String

    compileSdk {
        version = release(rootProject.extra.get("compileSdkVersion") as Int)
    }

    packaging {
        resources {
            excludes.addAll(
                listOf(
                    "META-INF/**/LICENSE*",
                    "META-INF/**/NOTICE*",
                    "META-INF/*.version",
                    "DebugProbesKt.bin"
                )
            )
        }
        dex {
            //强制压缩Dex
            useLegacyPackaging = true
        }
    }

    defaultConfig {
        applicationId = rootProject.extra["appPackageName"] as String
        minSdk = rootProject.extra["minSdkVersion"] as Int
        targetSdk = rootProject.extra["targetSdkVersion"] as Int
        versionCode = rootProject.extra["appVersionCode"] as Int
        versionName = rootProject.extra["appVersionName"] as String

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 签名信息来源优先级：环境变量（CI 用 secrets）> 仓库根目录 signing.properties（本地用，已 gitignore）
    val signingProps = Properties().apply {
        val propsFile = rootProject.file("signing.properties")
        if (propsFile.isFile) {
            propsFile.inputStream().use { load(it) }
        }
    }
    fun signingValue(key: String): String? =
        System.getenv(key) ?: signingProps.getProperty(key)?.takeIf { it.isNotBlank() }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(signingValue("RELEASE_STORE_FILE") ?: "release.jks")
            storePassword = signingValue("RELEASE_STORE_PASSWORD")
            keyAlias = signingValue("RELEASE_KEY_ALIAS")
            keyPassword = signingValue("RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("release")
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    // 可通过 -PABI_LIST=arm64-v8a,armeabi-v7a,x86_64,x86 或环境变量 ABI_LIST 覆盖。
    // 传 universal（或空）则关闭分包，产出单个通用包。
    val abiListRaw = providers.gradleProperty("ABI_LIST").orNull
        ?: providers.environmentVariable("ABI_LIST").orNull
        ?: "arm64-v8a"
    val abiList = abiListRaw
        .split(',', ' ', ';')
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.equals("universal", ignoreCase = true) }

    splits {
        abi {
            isEnable = abiList.isNotEmpty()
            reset()
            if (abiList.isNotEmpty()) {
                include(*abiList.toTypedArray())
            }
        }
    }
}

dependencies {
    implementation(project(":app"))
    implementation(project(":xposed"))

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
