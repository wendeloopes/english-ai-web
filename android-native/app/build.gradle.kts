plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.englishai.app"
    compileSdk = 35
    defaultConfig { applicationId = "com.englishai.app"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "1.0-native" }
}
dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")
}
