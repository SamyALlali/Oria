plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "com.oria.silmo.validation"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.oria.silmo.validation"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0-test-only"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    kotlinOptions { jvmTarget = "11" }
    sourceSets {
        getByName("main") {
            java.srcDirs("../../android-project/app/src/main/java/com/htc/vive/eagle/hackathon/starter/oria/core", "../../android-project/app/src/main/java/com/htc/vive/eagle/hackathon/starter/oria/ml", "../../android-project/app/src/main/java/com/htc/vive/eagle/hackathon/starter/oria/video")
            assets.srcDir("../../android-project/app/src/main/assets")
        }
        getByName("androidTest") {
            java.setSrcDirs(listOf(
                "../../android-project/app/src/androidTest/java/com/htc/vive/eagle/hackathon/starter/oria/ml",
                "../../android-project/app/src/androidTest/java/com/htc/vive/eagle/hackathon/starter/oria/video",
            ))
            assets.srcDir("../../android-project/app/src/androidTest/assets")
        }
    }
}
dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
