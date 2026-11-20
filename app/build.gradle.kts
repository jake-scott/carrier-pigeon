plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.jakethesnake.carrierpigeon"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.jakethesnake.carrierpigeon"
        minSdk = 30
        targetSdk = 36
        // Release APKs (and F-Droid, which rebuilds them from the tag) take
        // these from the source, so a release is reproducible. Only dev APKs
        // and Play bundles override the code, with -PdevVersionCode and
        // -PplayVersionCode (release.yml). An rc tag's Play bundle takes
        // versionName from the tag (-PplayVersionName), and a vX.Y.Z tag must
        // match versionName here, so Play and F-Droid users see exactly
        // X.Y.Z. dev and debug builds add the commit to it (see
        // androidComponents below).
        versionCode = 1
        versionName = "0.1.0"
    }

    // dev is the Firebase testers' channel: its own package name and launcher
    // label, signed with the dev key, so it installs beside the real app and
    // can never be updated over it. internal is Play internal testing (main
    // pushes): its own package too, so it's a separate app in Play Console
    // and installs beside the closed-testing or production app. prod is what
    // Play (closed track, then production), F-Droid and GitHub releases get.
    // See docs/signing.md.
    flavorDimensions += "channel"
    productFlavors {
        create("dev") {
            dimension = "channel"
            applicationIdSuffix = ".dev"
            providers.gradleProperty("devVersionCode").orNull?.let { versionCode = it.toInt() }
        }
        create("internal") {
            dimension = "channel"
            applicationIdSuffix = ".internal"
            providers.gradleProperty("playVersionCode").orNull?.let { versionCode = it.toInt() }
        }
        create("prod") {
            dimension = "channel"
            // Play bundles from rc tags only. F-Droid never builds them, so
            // the release APK keeps the code above. An rc tag's bundle also
            // takes its versionName from the tag (vX.Y.Z-rc.N gives X.Y.Z).
            providers.gradleProperty("playVersionCode").orNull?.let { versionCode = it.toInt() }
            providers.gradleProperty("playVersionName").orNull?.let { versionName = it }
        }
    }

    buildTypes {
        // Debug builds (build.yml's sideload artifact) get their own package
        // too, signed with the throwaway debug key, so they install beside
        // the dev and release builds.
        debug {
            applicationIdSuffix = ".debug"
            // Pick "English (XA)" (long, accented text) or "Arabic (XB)" (right to left) in the
            // system language settings to check layouts without a translation.
            isPseudoLocalesEnabled = true
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

    // Lists the languages in values-*/ for Android 13+'s per-app language setting. The default
    // values/ folder is en-US (res/resources.properties). Only add a values-xx folder once that
    // language is fully translated and reviewed, because it shows up in the setting.
    androidResources {
        generateLocaleConfig = true
    }

    lint {
        // Translation and right-to-left mistakes fail the build. Compose Text("...") literals
        // aren't covered by HardcodedText; scripts/check_hardcoded_strings.sh catches those.
        error += listOf(
            "MissingTranslation",
            "ExtraTranslation",
            "Untranslatable",
            "StringFormatInvalid",
            "StringFormatCount",
            "StringFormatMatches",
            "MissingQuantity",
            "ImpliedQuantity",
            "UnusedQuantity",
            "HardcodedText",
            "SetTextI18n",
            "RtlHardcoded",
            "RtlSymmetry",
        )
    }

    testOptions {
        // Android classes in unit tests return defaults instead of throwing "Stub!".
        unitTests.isReturnDefaultValues = true
    }

    // Keep F-Droid reproducible builds possible: no dependency metadata blob in
    // the APK (it is encrypted with a Google key, so F-Droid can't check it).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

// dev and debug builds say which commit they are, for example
//   devRelease (Firebase)            0.1.0-dev.15+11a78e2
//   devDebug                         0.1.0-dev-debug.15+11a78e2
//   prodDebug  (build.yml sideload)  0.1.0-debug.15+11a78e2
// 15 is the number of commits in HEAD's history, so it only grows along a
// branch (dev never contains main's tags, so "commits since the last tag"
// wouldn't work there), and 11a78e2 is HEAD's short SHA. A local build with
// uncommitted changes adds ".dirty". prodRelease (Play, F-Droid, GitHub
// releases) is left as versionName: no git, so F-Droid's shallow clone
// builds the same APK.
val gitDescription: Provider<String> = run {
    fun git(vararg args: String) = providers.exec {
        commandLine("git", *args)
        isIgnoreExitValue = true
    }.standardOutput.asText.map { it.trim() }
    val count = git("rev-list", "--count", "HEAD")
    val sha = git("rev-parse", "--short=7", "HEAD")
    val shallow = git("rev-parse", "--is-shallow-repository")
    val dirty = git("status", "--porcelain", "--untracked-files=no")
    count.zip(shallow) { c, sh -> c.takeIf { sh == "false" }.orEmpty() }
        .zip(sha) { c, s -> c to s }
        .zip(dirty) { (c, s), d ->
            if (s.isEmpty()) {
                "+unknown" // not a git checkout
            } else {
                // A shallow clone's count is meaningless, so leave it out.
                (if (c.isEmpty()) "" else ".$c") + "+$s" + (if (d.isEmpty()) "" else ".dirty")
            }
        }
}

androidComponents {
    onVariants { variant ->
        val label = listOfNotNull(
            "dev".takeIf { variant.flavorName == "dev" },
            "debug".takeIf { variant.buildType == "debug" },
        ).joinToString("-")
        if (label.isNotEmpty()) {
            variant.outputs.forEach { output ->
                val base = output.versionName.orNull.orEmpty()
                output.versionName.set(gitDescription.map { git -> "$base-$label$git" })
            }
        }
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
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
