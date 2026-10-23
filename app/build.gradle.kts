plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.v3x.laczekplayer"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.v3x.laczekplayer"
        minSdk = 24
        targetSdk = 35
        versionCode = 15
        versionName = "2.5.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media3:media3-common:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-audio:8.1.7")
}
