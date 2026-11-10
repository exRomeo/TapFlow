import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

// Release signing comes from keystore.properties (not in git). Without it, release builds
// fall back to the debug key, which is fine for testing but not for Google Play.
val keystoreProps = Properties().apply {
  rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

android {
  namespace = "dev.tapflow.autoclicker"
  compileSdk = 37
  defaultConfig {
    applicationId = "dev.tapflow.autoclicker"
    minSdk = 26
    targetSdk = 37
    versionCode = 3
    versionName = "0.3.0"
  }

  signingConfigs {
    if (keystoreProps.isNotEmpty()) {
      create("upload") {
        storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
        storePassword = keystoreProps.getProperty("storePassword")
        keyAlias = keystoreProps.getProperty("keyAlias")
        keyPassword = keystoreProps.getProperty("keyPassword")
      }
    }
  }

  buildTypes {
    debug {
      // OpenCV's native library is large for each CPU type, so debug builds carry only
      // arm64 (phones). Build with -Pemulator to add x86_64 for an emulator.
      ndk {
        abiFilters += listOf("arm64-v8a")
        if (project.hasProperty("emulator")) abiFilters += listOf("x86_64")
      }
    }
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // The App Bundle splits native code per CPU type, so each phone downloads only its own.
      ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
      signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    aidl = false
    buildConfig = false
    shaders = false
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.activity.compose)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.opencv)

  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  debugImplementation(libs.androidx.compose.ui.tooling)

  testImplementation(libs.junit)
}
