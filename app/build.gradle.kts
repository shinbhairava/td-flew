import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

val telegramApiId = (env("API_ID") ?: env("TELEGRAM_API_ID"))?.toIntOrNull() ?: 4
val telegramApiHash = env("API_HASH")
    ?: env("TELEGRAM_API_HASH")
    ?: "014b35b6184100b085b0d0572f9b5103"

val appVersionName = env("APP_VERSION_NAME") ?: "1.0.0"
val appVersionCode = env("APP_VERSION_CODE")?.toIntOrNull() ?: 1

val releaseKeystorePath = env("ANDROID_KEYSTORE_PATH")
val releaseKeystoreFile = releaseKeystorePath?.let { file(it) }
val releaseStorePassword = env("ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = env("ANDROID_KEY_ALIAS")
val releaseKeyPassword = env("ANDROID_KEY_PASSWORD")
val hasReleaseSigning = releaseKeystoreFile?.exists() == true &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()

fun quoted(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "com.shinbhairava.tdflew"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.shinbhairava.tdflew"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = releaseKeystoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    buildTypes.configureEach {
        buildConfigField("String", "GITHUB_REPO_URL", quoted("https://github.com/shinbhairava/td-flew"))
        buildConfigField("String", "GITHUB_REPO_LABEL", quoted("shinbhairava/td-flew"))
        buildConfigField("int", "TELEGRAM_API_ID", telegramApiId.toString())
        buildConfigField("String", "TELEGRAM_API_HASH", quoted(telegramApiHash))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)

    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
