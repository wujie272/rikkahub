import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "me.rerere.locallm"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        // Return default values (null / 0 / false) for Android framework calls in JVM unit
        // tests instead of throwing "not mocked" exceptions. Required because production
        // code (e.g. LiteRtToolPrefix) calls android.util.Log which isn't available on JVM.
        unitTests.isReturnDefaultValues = true
    }
    sourceSets {
        getByName("main").jniLibs.setSrcDirs(listOf("src/main/jniLibs"))
    }
    packaging {
        jniLibs {
            // Avoid extracting native libs at install time so System.loadLibrary path stays cheap.
            useLegacyPackaging = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
        optIn.add("kotlin.time.ExperimentalTime")
    }
}

dependencies {
    api(project(":ai"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    // LiteRT-LM runtime: loads .litertlm model files produced by the LiteRT-LM toolchain.
    //
    // Updated to 0.14.0 (2026-07-08) from 0.11.0. Key changes:
    //   - Reasoning/thinking channels support
    //   - Auto-backend selection for audio & vision models
    //   - Tool calling streaming
    //   - Android CLI/Python support (can run directly in Termux)
    //
    // The 0.11.0 pin was because 0.12.0 native-SIGSEGV'd on Snapdragon 8 Gen 1 (Adreno 642L)
    // during vision-encoder init. 0.14.0's auto-backend selection likely resolves this;
    // the runtime's GPU→CPU fallback still backstops any remaining device-specific issues.
    // See LiteRtRuntime.kt's ensureLoaded() for the fallback logic.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.14.0")

    testImplementation(libs.junit)
}
