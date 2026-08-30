plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.android.junit5) apply false
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
}

// One detekt run for the whole repo; module code is all Kotlin under these roots.
detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt.yml"))
    source.setFrom(
        files(
            "app/src",
            "core/data/src",
            "core/media/src",
            "core/network/src",
            "core/crypto/src",
            "core/testing/src",
            "sync/src",
            "build-logic/src",
        ),
    )
}

// Merged coverage: the root kover report aggregates the gated modules.
dependencies {
    kover(project(":core:data"))
    kover(project(":core:crypto"))
    kover(project(":sync"))
}
