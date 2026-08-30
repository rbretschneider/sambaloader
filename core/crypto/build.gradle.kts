plugins {
    id("sambaloader.android.library")
    alias(libs.plugins.kover)
}

android {
    namespace = "com.nectarmobiledevelopment.sambaloader.core.crypto"
}

kover {
    reports {
        verify {
            rule {
                minBound(80)
            }
        }
    }
}
