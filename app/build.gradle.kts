import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.android.junit5)
}

// Release signing: CI provides env vars, local builds read key.properties
// (gitignored); with neither, release falls back to debug signing so a
// fresh clone still builds.
val keyProperties = Properties().apply {
    val file = rootProject.file("key.properties")
    if (file.exists()) {
        file.inputStream().use { stream -> load(stream) }
    }
}

fun signingValue(env: String, property: String): String? {
    return System.getenv(env) ?: keyProperties.getProperty(property)
}

android {
    namespace = "com.nectarmobiledevelopment.sambaloader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nectarmobiledevelopment.sambaloader"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        create("release") {
            val storePath = signingValue("KEYSTORE_FILE", "storeFile")
            if (storePath != null) {
                storeFile = rootProject.file(storePath)
                storePassword = signingValue("KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("KEY_ALIAS", "keyAlias")
                keyPassword = System.getenv("KEY_PASSWORD")
                    ?: signingValue("KEYSTORE_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Minification stays off until S7.4 verifies R8 keep rules for
            // BouncyCastle/OkHttp/keystore reflection — a broken-at-runtime
            // "optimized" build is worse than a slightly larger working one.
            isMinifyEnabled = false
            signingConfig = if (signingValue("KEYSTORE_FILE", "storeFile") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            // The three BouncyCastle jars each ship OSGI metadata under the
            // same path; none of it is needed at runtime.
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:crypto"))
    implementation(project(":core:media"))
    implementation(project(":core:system"))
    implementation(project(":sync"))
    implementation(libs.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.zxing.embedded)
    implementation(project(":core:network"))
    implementation(libs.okhttp)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":core:testing"))
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.runtime)
    testRuntimeOnly(libs.junit.vintage.engine)
    testRuntimeOnly(libs.junit.jupiter.engine)
}
