import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.chaquopy)
}

// ---------------------------------------------------------------- signing secrets
// Read from <project>/.env (git-ignored). Real environment variables win, so CI can
// inject them without a file. See docs/BUILD.md -> "Signing".
val dotenv: Map<String, String> = rootProject.file(".env").let { f ->
    if (!f.exists()) emptyMap()
    else f.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
        .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim().removeSurrounding("\"") }
}

fun secret(key: String): String? = System.getenv(key)?.takeIf { it.isNotBlank() } ?: dotenv[key]

val hasReleaseKey = secret("CALC_KEYSTORE_PATH")?.let { rootProject.file(it).exists() } == true

// ABIs to package. Default: real phones only (arm64-v8a). For an x86_64 emulator:
//   ./gradlew assembleDebug -Pcalc.abis=arm64-v8a,x86_64
val abis = (findProperty("calc.abis") as String? ?: "arm64-v8a").split(',').map { it.trim() }

android {
    namespace = "com.mattia.calculator"
    // Android 17 = API 37. Compose 1.13 needs the 37.1+ minor SDK at compile time;
    // targetSdk stays 37 (minor levels cannot be targeted).
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "com.mattia.calculator"
        minSdk = 26                       // Android 8.0 (Chaquopy needs 24+, java.time needs 26)
        targetSdk = 37                    // Android 17 behaviour changes opted in
        versionCode = 1
        versionName = "1.0.0"
        ndk { abiFilters += abis }
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKey) {
                storeFile = rootProject.file(secret("CALC_KEYSTORE_PATH")!!)
                storePassword = secret("CALC_KEYSTORE_PASSWORD")
                keyAlias = secret("CALC_KEY_ALIAS")
                keyPassword = secret("CALC_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

chaquopy {
    defaultConfig {
        version = "3.14"                  // matches the Python used to build/test the engine
        pip {
            install("sympy==1.14.0")
            install("mpmath==1.3.0")
        }
    }
}

// Chaquopy 17.2 extracts its R8 keep rules to build/python/proguard-rules.pro when the plugin is
// APPLIED (configuration time). After `clean`, a configuration-cache hit skips that step and R8
// fails with "Supplied proguard configuration does not exist". Re-extract it, from the plugin's
// own jar, right before R8 runs. See docs/BUILD.md -> "Known build quirks".
// (No declared output on purpose: the file is "owned" by Chaquopy; the task is trivial and always runs.)
val restoreChaquopyProguardRules = tasks.register("restoreChaquopyProguardRules") {
    val target = layout.buildDirectory.file("python/proguard-rules.pro")   // local: config-cache safe
    mustRunAfter("clean")
    doLast {
        val bytes = com.chaquo.python.PythonPlugin::class.java.getResource("proguard-rules.pro")!!.readBytes()
        target.get().asFile.apply { parentFile.mkdirs(); writeBytes(bytes) }
    }
}
tasks.named("preBuild") { dependsOn(restoreChaquopyProguardRules) }

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
