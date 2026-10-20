plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.astrbot.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = (project.findProperty("testAppId") as String?) ?: "com.astrbot.mobile"
        minSdk = 24
        // targetSdk 28：Android 10+ 禁止 targetSdk≥29 的 App 执行其数据目录中的
        // 二进制文件（SELinux untrusted_app 域限制），内嵌 proot 运行时依赖该能力，
        // 与 Termux 采用相同策略。
        targetSdk = 28
        versionCode = 5
        versionName = "2.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    lint {
        // targetSdk=28 是架构要求，不是疏漏：Android 10+ 禁止 targetSdk≥29 的 App
        // 执行数据目录内的二进制，proot 会失效。Google Play 的上架规则不适用于本
        // 项目（直接分发 APK），因此显式关闭这条检查，避免 release 构建被阻断。
        disable += "ExpiredTargetSdkVersion"
        checkReleaseBuilds = false
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // 解压 .so 到 native lib 目录，使内嵌的 proot 可执行
            useLegacyPackaging = true
        }
    }

    androidResources {
        // 内置 rootfs 本身就是 gzip 压缩流，AAPT 再压一次只会白白增加体积与解压开销。
        // 用 .bin 扩展名还能避开 AAPT2 对 .gz/.tar 的自动解包改名行为。
        noCompress += listOf("bin")
    }
}

// Force use of ARM64 binaries for AAPT2 in Proot environment
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "com.android.tools.build" && requested.name == "aapt2") {
            useTarget("com.android.tools.build:aapt2:${'$'}{requested.version}:linux-aarch64")
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.icons.extended)
    // Shizuku：以 shell(uid2000)身份启动服务端，脱离 App 进程族，免疫厂商冻结器
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
