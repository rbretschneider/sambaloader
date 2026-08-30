plugins {
    id("sambaloader.android.library")
}

android {
    namespace = "com.nectarmobiledevelopment.sambaloader.core.testing"
}

dependencies {
    implementation(project(":core:crypto"))
    api(project(":core:network"))
    implementation(libs.gson)
    api(libs.okhttp.tls)
}
