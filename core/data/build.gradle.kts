plugins {
    id("sambaloader.android.library")
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kover)
}

android {
    namespace = "com.nectarmobiledevelopment.sambaloader.core.data"

    sourceSets {
        getByName("test") {
            // Exported Room schemas feed MigrationTestHelper.
            assets.srcDir("$projectDir/schemas")
        }
    }
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
                    // Room- and Hilt-generated implementations: exercised
                    // through the repositories, but not our code to cover.
                    "*_Impl",
                    "*_Impl\$*",
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
    testImplementation(libs.room.testing)
    testRuntimeOnly(libs.junit.vintage.engine)
}

ksp {
    // Exported schemas feed the migration tests required from version 2 on.
    arg("room.schemaLocation", "$projectDir/schemas")
}
