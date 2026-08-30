plugins {
    id("sambaloader.android.library")
}

android {
    namespace = "com.nectarmobiledevelopment.sambaloader.core.network"
}

dependencies {
    implementation(libs.okhttp)
    testImplementation(libs.okhttp.mockwebserver)
}
