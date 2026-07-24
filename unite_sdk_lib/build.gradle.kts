plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

val aarName = (project.findProperty("UNITE_SDK_AAR_NAME") as String?) ?: "unite-sdk"
val aarVersion = (project.findProperty("UNITE_SDK_AAR_VERSION") as String?) ?: "1.1.0"

base {
    archivesName.set(aarName)
}

android {
    namespace = "com.unite.sdk"
    compileSdk = 36

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        minSdk = 21

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

val aarOutDir = layout.buildDirectory.dir("outputs/aar")

tasks.register<org.gradle.api.tasks.Copy>("exportReleaseAar") {
    dependsOn("assembleRelease")
    from(aarOutDir)
    include("*-release.aar")
    into(aarOutDir)
    rename { "${aarName}-${aarVersion}.aar" }
}

tasks.register<org.gradle.api.tasks.Copy>("exportDebugAar") {
    dependsOn("assembleDebug")
    from(aarOutDir)
    include("*-debug.aar")
    into(aarOutDir)
    rename { "${aarName}-${aarVersion}-debug.aar" }
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy("exportReleaseAar")
}

tasks.matching { it.name == "assembleDebug" }.configureEach {
    finalizedBy("exportDebugAar")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation("androidx.browser:browser:1.8.0")
    implementation("io.coil-kt:coil:2.6.0")
    implementation("io.coil-kt:coil-svg:2.6.0")

    // ExoPlayer (AndroidX Media3)
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
}
