plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.codeg0blin.videowallpaper"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.codeg0blin.videowallpaper"
        minSdk = 26
        targetSdk = 36
        versionCode = 11
        versionName = "2.0.0"
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
