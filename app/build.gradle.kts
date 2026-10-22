import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val signing = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
}
fun signingProperty(name: String, local: String): String? =
    providers.gradleProperty(name).orNull ?: signing.getProperty(local)

android {
    namespace = "com.jasongzy.frpmobile"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    buildToolsVersion = "37.0.0"
    ndkVersion = "30.0.16248370"
    defaultConfig {
        applicationId = "com.jasongzy.frpmobile"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = providers.gradleProperty("VERSION_CODE").get().toInt()
        versionName = providers.gradleProperty("VERSION_NAME").get()
        buildConfigField("String", "FRP_VERSION", "\"${providers.gradleProperty("FRP_VERSION").get()}\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    signingConfigs {
        getByName("debug") {
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = false
        }
        create("release") {
            storeFile = signingProperty("RELEASE_STORE_FILE", "storeFile")?.let(::file)
            storePassword = signingProperty("RELEASE_STORE_PASSWORD", "storePassword")
            keyAlias = signingProperty("RELEASE_KEY_ALIAS", "keyAlias")
            keyPassword = signingProperty("RELEASE_KEY_PASSWORD", "keyPassword")
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = false
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    packaging { jniLibs.useLegacyPackaging = true }
    androidResources { localeFilters += listOf("en", "zh-rCN") }
    lint { abortOnError = true }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(project(":core"))
    implementation(libs.tasker.plugin)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.animation)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui)
    implementation(libs.material.kolor)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit)
}

val verifyNativeBinaries = tasks.register("verifyNativeBinaries") {
    val binaries = listOf("arm64-v8a", "armeabi-v7a", "x86_64").flatMap { abi ->
        listOf("frpc", "frps", "frpmobile").map { file("src/main/jniLibs/$abi/lib$it.so") }
    }
    inputs.files(binaries)
    val fields = file("src/main/assets/frp-fields.json")
    val frpVersion = providers.gradleProperty("FRP_VERSION").get()
    inputs.file(fields)
    inputs.property("frpVersion", frpVersion)
    doLast {
        binaries.forEach { binary ->
            check(binary.isFile && binary.length() > 500_000) {
                "Missing native core: ${binary.name}. Run python ci/build-native.py before building."
            }
        }
        check(fields.isFile) { "Missing frp completion fields. Run python ci/build-native.py before building." }
        val metadata = groovy.json.JsonSlurper().parse(fields) as Map<*, *>
        check(metadata["frpVersion"] == frpVersion) {
            "Stale frp completion fields. Run python ci/build-native.py before building."
        }
    }
}
tasks.named("preBuild").configure { dependsOn(verifyNativeBinaries) }
