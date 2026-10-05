plugins {
    alias(libs.plugins.android.application)
}

// Optional stable signing key, so CI builds install over each other. See README.
val kotoKeystore: File? = System.getenv("KOTO_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "koto.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.newmanpng.kotoamatsukami"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (kotoKeystore != null) {
            create("koto") {
                storeFile = kotoKeystore
                storePassword = System.getenv("KOTO_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KOTO_KEY_ALIAS")
                keyPassword = System.getenv("KOTO_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            if (kotoKeystore != null) signingConfig = signingConfigs.getByName("koto")
        }
        release {
            isMinifyEnabled = false
            signingConfig = if (kotoKeystore != null) signingConfigs.getByName("koto") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.play.services.location)
}
