plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    namespace = "com.englishai.app"
    compileSdk = 35
    defaultConfig { applicationId = "com.englishai.app"; minSdk = 26; targetSdk = 35; versionCode = 7; versionName = "1.3.4"; ndk { abiFilters += listOf("arm64-v8a") } }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")
    implementation("com.google.mlkit:translate:17.0.3")
}
