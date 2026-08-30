plugins {
    id("sambaloader.android.library")
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kover)
}

android {
    namespace = "com.nectarmobiledevelopment.sambaloader.core.crypto"
}

dependencies {
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.bouncycastle.bcpkix)
}

kover {
    reports {
        filters {
            excludes {
                // Requires a real AndroidKeyStore — covered by instrumented
                // tests on device, not JVM unit tests.
                classes(
                    "*.identity.AndroidKeyStoreKeyPairProvider",
                    "*.di.*",
                    "hilt_aggregated_deps.*",
                    "*_Factory*",
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
