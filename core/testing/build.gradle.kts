plugins {
    id("sambaloader.android.library")
}

android {
    namespace = "com.nectarmobiledevelopment.sambaloader.core.testing"
}

dependencies {
    implementation(project(":core:crypto"))
    implementation(libs.gson)
}
