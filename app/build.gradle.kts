plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.codeg0blin.videowallpaper"
    compileSdk = 37

    defaultConfig {
        // Own ID for the fork, so it never collides with the original app
        // (io.github.codeg0blin.videowallpaper, distributed on F-Droid).
        // The Kotlin package / namespace keeps the original name.
        applicationId = "cz.pribil.videowallpaper"
        minSdk = 26
        targetSdk = 36
        versionCode = 12
        versionName = "2.0.1"
    }

    // Release signing. The keystore and passwords never live in the repository:
    // locally they come from ~/.gradle/gradle.properties, in CI from the
    // ORG_GRADLE_PROJECT_vlw* environment variables (GitHub Secrets). Without
    // them, assembleRelease still works and produces an unsigned APK.
    val signingStoreFile = providers.gradleProperty("vlwStoreFile").orNull
    val releaseSigning = if (signingStoreFile != null) {
        signingConfigs.create("release") {
            storeFile = file(signingStoreFile)
            storePassword = providers.gradleProperty("vlwStorePassword").get()
            keyAlias = providers.gradleProperty("vlwKeyAlias").get()
            keyPassword = providers.gradleProperty("vlwKeyPassword").get()
        }
    } else {
        null
    }

    buildTypes {
        debug {
            // Installs next to a release build instead of clashing with its signature.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // R8 shrinks Media3/Material/AppCompat down to what the app
            // actually uses; see proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = releaseSigning
        }
        // The release build (R8-shrunk) signed with the debug key and given
        // its own ID, so it can be installed on a test phone next to the
        // real app to check that shrinking didn't break anything.
        create("staging") {
            initWith(getByName("release"))
            applicationIdSuffix = ".staging"
            versionNameSuffix = "-staging"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }

    // F-Droid reproducibility: no Google-signed dependency metadata blob.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")

    testImplementation("junit:junit:4.13.2")
}
