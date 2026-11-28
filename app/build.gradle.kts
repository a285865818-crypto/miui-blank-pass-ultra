import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 签名与签名校验值都从 local.properties 读（该文件不入库）。
// 开源版本 clone 下来即使没有 local.properties 也能 assembleDebug 出未签名包。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    // UTF-8 读取：keystore 别名是中文，Properties.load(InputStream) 会按 ISO-8859-1 解码成乱码
    if (f.exists()) f.reader(Charsets.UTF_8).use { load(it) }
}
val signStoreFileName: String? = localProps.getProperty("SIGN_STORE_FILE")
val expectedSigHash: String = localProps.getProperty("EXPECTED_SIG_SHA256") ?: ""

android {
    namespace = "io.github.sweetpotato_digua.mbpu"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "io.github.sweetpotato_digua.mbpu"
        minSdk = 29
        targetSdk = 37
        versionCode = 15
        versionName = "1.4.2"
        // 期望的签名证书 SHA-256（小写 hex）。留空 = 不校验签名，方便他人构建。
        // N12 修复：转义 Java 字符串里的反斜杠、双引号和控制字符，
        // 避免 local.properties 里的值含这些字符时生成非法 BuildConfig.java。
        val escapedSigHash = expectedSigHash
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        buildConfigField("String", "EXPECTED_SIG_SHA256", "\"$escapedSigHash\"")
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        // 签名信息来自 local.properties（SIGN_STORE_FILE / SIGN_STORE_PASSWORD /
        // SIGN_KEY_ALIAS / SIGN_KEY_PASSWORD），仓库里不含任何 keystore 与密码。
        create("release") {
            if (signStoreFileName != null) {
                storeFile = file(signStoreFileName)
                storePassword = localProps.getProperty("SIGN_STORE_PASSWORD")
                keyAlias = localProps.getProperty("SIGN_KEY_ALIAS")
                keyPassword = localProps.getProperty("SIGN_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (signStoreFileName != null) signingConfig = signingConfigs.getByName("release")
        }
        getByName("release") {
            // 发行版：开 R8 + 资源压缩（混淆加固）。
            // 卡顿/掉帧主要来自未优化的 debug 字节码：release 开 R8 + 资源压缩，
            // Compose 的运行时开销会明显下降。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (signStoreFileName != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        // JVM 21：miuix-nav 的 inline DSL 按 21 构建（rc01 上游 JDK 21），低版本无法内联。
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // native 安全层：so 由 build_native.sh 手动编译（NDK 的 ndk-build/cmake
    // 在 aarch64 host 上跑不起来），产物放 jniLibs/ 目录由 Gradle 打包。

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java", "src/main/kotlin")
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/*.version",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
    }
}

dependencies {
    // modern Xposed API：由 LSPosed 在 hook 时注入，不能打进 APK
    compileOnly("io.github.libxposed:api:102.0.0")
    // service / interface 必须随 APK 打包，框架通过 ContentProvider 与本 app 通信
    implementation("io.github.libxposed:service:102.0.0")

    implementation("androidx.core:core-ktx:1.16.0")
    // 版本对齐 Miuix 官方 example（gradle/libs.versions.toml: androidx-activity = 1.13.0）。
    // 弹层内部使用 NavigationBackHandler，需要 ComponentActivity 提供
    // LocalNavigationEventDispatcherOwner；1.10.1 没有该实现，会在弹层显示时崩溃。
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui:1.12.0")
    implementation("androidx.compose.foundation:foundation:1.12.0")

    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.5-rc01")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.5-rc01")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.5-rc01")
    // 高级材质（玻璃 / 渐变模糊）。库本身声明 minSdk 33，
    // 这里用 tools:overrideLibrary 合并，运行时用 isRuntimeShaderSupported() 守卫降级。
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.5-rc01")
    // 0.9.5 起描边/光源体系（EdgeStroke、isRuntimeShaderSupported）从 blur 迁到 material。
    implementation("top.yukonga.miuix.kmp:miuix-material-android:0.9.5-rc01")
    // rc01 官方 Glass 组件家族（GlassTopAppBar、GlassIconButton），替掉自研玻璃顶栏/圆钮。
    implementation("top.yukonga.miuix.kmp:miuix-glass-android:0.9.5-rc01")
    // 官方页面栈与转场（NavDisplay + Expansion），替掉自研导航栈。
    implementation("top.yukonga.miuix.kmp:miuix-nav-android:0.9.5-rc01")
}
