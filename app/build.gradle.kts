plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.jakescott.carrierpigeon"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.jakescott.carrierpigeon"
        minSdk = 30
        targetSdk = 36
        // qa and prod builds (and F-Droid, which rebuilds them from the tag)
        // take these from the source, so a release is reproducible. Only dev
        // builds override the code, with -PdevVersionCode (release.yml).
        versionCode = 1
        versionName = "0.1.0-poc"
    }

    // dev is the internal channel: its own package name and launcher label,
    // signed with the dev key, so it installs beside the real app and can
    // never be updated over it. prod is what qa testers, F-Droid, GitHub
    // releases and Play get (qa is a prerelease of the same app, signed with
    // the same key). See docs/signing.md.
    flavorDimensions += "channel"
    productFlavors {
        create("dev") {
            dimension = "channel"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            providers.gradleProperty("devVersionCode").orNull?.let { versionCode = it.toInt() }
        }
        create("prod") {
            dimension = "channel"
        }
    }

    buildTypes {
        // Debug builds (the sideload artifact from pushes to dev) get their
        // own package too, signed with the throwaway debug key, so they
        // install beside the insiders and release builds.
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Release builds are left unsigned here. CI signs them with keys in
            // Cloud KMS that never leave it (scripts/kms_sign.sh), and F-Droid
            // copies that signature onto its own rebuild.
            //
            // Reproducible builds: the git commit AGP would otherwise record in
            // META-INF/version-control-info.textproto depends on the checkout
            // (F-Droid's build server, a shallow clone), so leave it out.
            vcsInfo.include = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Keep F-Droid reproducible builds possible: no dependency metadata blob in
    // the APK (it is encrypted with a Google key, so F-Droid can't check it).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
}
