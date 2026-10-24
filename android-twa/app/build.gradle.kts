plugins {
    id("com.android.application")
}

android {
    namespace = "com.englishai.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.englishai.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ""
        }
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("com.google.androidbrowserhelper:androidbrowserhelper:2.7.4")
}
