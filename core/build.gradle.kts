plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.jasongzy.frpmobile.core"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    ndkVersion = "30.0.16248370"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    testOptions { targetSdk = libs.versions.targetSdk.get().toInt() }
    packaging { jniLibs.useLegacyPackaging = true }
}

androidComponents {
    onVariants { variant ->
        variant.androidTest?.sources?.jniLibs?.addStaticSourceDirectory("../app/src/main/jniLibs")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.tomlj)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit)
}
