plugins {
    id("sambaloader.android.library")
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kover)
}

android {
    namespace = "com.nectarmobiledevelopment.sambaloader.sync"
}

kover {
    reports {
        filters {
            excludes {
                // Generated DI plumbing only; every hand-written class in
                // this module is gated.
                classes(
                    "*.di.*",
                    "*_Factory*",
                    "*_AssistedFactory*",
                    "*.HiltWrapper*",
                    "hilt_aggregated_deps.*",
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
    implementation(project(":core:data"))
    implementation(project(":core:media"))
    implementation(project(":core:crypto"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(project(":core:testing"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.work.testing)
    testImplementation(libs.room.runtime)
    testRuntimeOnly(libs.junit.vintage.engine)
}
