plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.plugin.android.gradle)
    implementation(libs.plugin.kotlin.gradle)
    implementation(libs.plugin.android.junit5)
}

gradlePlugin {
    plugins {
        register("androidLibraryConvention") {
            id = "sambaloader.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
    }
}
