plugins {
    id("sambaloader.android.library")
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kover)
}

android {
    namespace = "com.nectarmobiledevelopment.sambaloader.core.data"
}

kover {
    reports {
        filters {
            excludes {
                // DI wiring and the raw system-clock boundary have no logic
                // to test; everything else in this module is gated.
                classes(
                    "*.di.*",
                    "*.time.SystemTimeProvider",
                    // Android-keystore-backed prefs need a device; covered by
                    // instrumented tests, not JVM unit tests.
                    "*.identity.EncryptedPrefsKeyValueStore",
                    "hilt_aggregated_deps.*",
                    "*_Factory*",
                    "*.DaggerHilt*",
                )
            }
        }
        verify {
            rule {
                minBound(80)
            }
        }
    }
}

dependencies {
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.security.crypto)

    api(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testRuntimeOnly(libs.junit.vintage.engine)
}

ksp {
    // Exported schemas feed the migration tests required from version 2 on.
    arg("room.schemaLocation", "$projectDir/schemas")
}
