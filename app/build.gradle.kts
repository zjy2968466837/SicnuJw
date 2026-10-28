import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "cn.edu.sicnu.scnujw"
    compileSdk = 37

    defaultConfig {
        applicationId = "cn.edu.sicnu.scnujw"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "1.5"

        // 应用内更新的清单地址：写在 local.properties（不入版本控制）或 -PupdateManifestUrl=…
        // 留空时整个功能关闭（不发任何更新请求）。命令行 -P 优先，方便本地联调。
        val localProps = Properties().apply {
            rootProject.file("local.properties")
                .takeIf { it.exists() }
                ?.inputStream()
                ?.use { load(it) }
        }
        val updateUrl = (providers.gradleProperty("updateManifestUrl").orNull
            ?: localProps.getProperty("updateManifestUrl")
            ?: "").trim()
        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"$updateUrl\"")

        // 统计服务地址。公开构建留空 = 不启用统计（APK 内不含任何自建服务地址）；
        // 自用构建在 local.properties 里配 statsEndpoint=https://<host>/v1/stats。
        val statsUrl = (providers.gradleProperty("statsEndpoint").orNull
            ?: localProps.getProperty("statsEndpoint")
            ?: "").trim()
        buildConfigField("String", "STATS_ENDPOINT", "\"$statsUrl\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // release 签名：仓库根 keystore.properties（不入版本控制，丢失则重新生成）
            val ksFile = rootProject.file("keystore.properties")
            if (ksFile.exists()) {
                val ks = Properties().apply { load(FileInputStream(ksFile)) }
                signingConfig = signingConfigs.create("release") {
                    storeFile = rootProject.file(ks.getProperty("storeFile"))
                    storePassword = ks.getProperty("storePassword")
                    keyAlias = ks.getProperty("keyAlias")
                    keyPassword = ks.getProperty("keyPassword")
                }
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // 脱敏/黑名单等纯逻辑用 JVM 单测兜住（不影响 APK）
    testImplementation("junit:junit:4.13.2")
}
