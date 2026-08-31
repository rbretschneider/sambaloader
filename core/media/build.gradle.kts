plugins {
    id("sambaloader.android.library")
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.nectarmobiledevelopment.sambaloader.core.media"
}

dependencies {
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testRuntimeOnly(libs.junit.vintage.engine)
}
