plugins {
    id("sambaloader.android.library")
    alias(libs.plugins.kover)
}

android {
    namespace = "com.nectarmobiledevelopment.sambaloader.sync"
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

dependencies {
    implementation(project(":core:data"))
    implementation(libs.kotlinx.coroutines.core)
}
