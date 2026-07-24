import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// 凭证与签名口令一律不进仓库：从 local.properties（已 gitignore）或环境变量读取。
// 开源/新 clone 环境缺省时用占位值，工程可编译，跑通需自行配置（见 README）。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun secret(key: String, fallback: String = ""): String =
    (localProps.getProperty(key) ?: System.getenv(key) ?: fallback)

val uniteClientId = secret("UNITE_CLIENT_ID", "YOUR_CLIENT_ID")
val uniteSecretKey = secret("UNITE_SECRET_KEY", "YOUR_SECRET_KEY")
val uniteUid = secret("UNITE_UID", "demo_user")
// 大厅游戏位（slot_type=gameCenter）的 slot_id，运营分配；为空时 demo 走旧 setConfig 固定 URL 路径
val uniteHallSlotId = secret("UNITE_HALL_SLOT_ID")

android {
    namespace = "com.unite.sdk.demo"
    compileSdk = 36

    signingConfigs {
        create("release") {
            storeFile = file("keystore/demo-release.jks")
            storePassword = secret("DEMO_STORE_PASSWORD")
            keyAlias = secret("DEMO_KEY_ALIAS", "demo")
            keyPassword = secret("DEMO_KEY_PASSWORD")
        }
    }


    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.unite.sdk.demo"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "UNITE_CLIENT_ID", "\"$uniteClientId\"")
        buildConfigField("String", "UNITE_SECRET_KEY", "\"$uniteSecretKey\"")
        buildConfigField("String", "UNITE_UID", "\"$uniteUid\"")
        buildConfigField("String", "UNITE_HALL_SLOT_ID", "\"$uniteHallSlotId\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }


}

dependencies {
    implementation(project(":unite_sdk_lib"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.swiperefreshlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
