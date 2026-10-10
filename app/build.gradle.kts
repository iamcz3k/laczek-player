plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.v3x.laczekplayer"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.v3x.laczekplayer"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }
}