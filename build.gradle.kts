// The Android Gradle plugin brings Bouncy Castle 1.80.2 onto the build's
// classpath (for signing), which has known vulnerabilities (GHSA-9pwp-9qqc-pr26,
// GHSA-qp49-qgx5-5m26, fixed in 1.85). It runs at build time only; nothing of
// it ends up in the app. Raise it until AGP does.
buildscript {
    dependencies {
        constraints {
            classpath(libs.bouncycastle.bcprov)
            classpath(libs.bouncycastle.bcpkix)
            classpath(libs.bouncycastle.bcutil)
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
