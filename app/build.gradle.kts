import io.gitlab.arturbosch.detekt.Detekt
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.detekt)
}

// The one place to bump before a release: versionCode follows (1.2.3 -> 10203).
val appVersion = "1.0.0"

android {
    namespace = "me.jstephan.carburants"
    compileSdk = 35

    defaultConfig {
        applicationId = "me.jstephan.carburants"
        // Android Automotive 12 (API 31) and later.
        minSdk = 31
        targetSdk = 35
        versionName = appVersion
        versionCode = appVersion.split(".").map(String::toInt).let { (major, minor, patch) ->
            major * 10_000 + minor * 100 + patch
        }
    }

    // Release signing from the environment (CI secrets); without it, release builds use the debug key.
    val keystore = System.getenv("SIGNING_KEYSTORE")?.let(::file)?.takeIf { it.exists() }
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = keystore
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = false
        sarifReport = true
        // Translations and Play-only checks don't apply to this French-only, sideload-tested app.
        disable += setOf("MissingTranslation", "GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    source.setFrom("src/main/java")
    parallel = true
}

tasks.withType<Detekt>().configureEach {
    jvmTarget = "17"
    reports {
        sarif.required.set(true)
        html.required.set(false)
        xml.required.set(false)
        txt.required.set(false)
        md.required.set(false)
    }
}

dependencies {
    implementation(libs.car.app)
    implementation(libs.car.app.automotive)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime)
    implementation(libs.coroutines.android)
    detektPlugins(libs.detekt.formatting)
}
