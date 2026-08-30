plugins {
    id("sambaloader.android.library")
}

android {
    namespace = "com.nectarmobiledevelopment.sambaloader.core.testing"
}

dependencies {
    api(project(":core:crypto"))
    api(project(":core:data"))
    api(project(":core:network"))
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.core)
    api(libs.okhttp.tls)
}
