package com.nectarmobiledevelopment.sambaloader.core.testing.testdata

import com.google.gson.Gson
import java.io.File

/**
 * Access to the repo's `testdata/` directory from JVM tests, regardless of
 * which module the test runs in (Gradle sets each test's working directory
 * to its own module).
 */
object TestData {

    private const val MANIFEST_RELATIVE_PATH = "testdata/manifest.json"
    private val gson = Gson()

    val root: File by lazy { findRepoRoot() }

    fun manifest(): List<TestAsset> {
        val json = File(root, MANIFEST_RELATIVE_PATH).readText()
        return gson.fromJson(json, Array<TestAsset>::class.java).toList()
    }

    fun file(asset: TestAsset): File {
        return File(root, "testdata/${asset.name}")
    }

    private fun findRepoRoot(): File {
        var candidate: File? = File(System.getProperty("user.dir")).absoluteFile
        while (candidate != null) {
            if (File(candidate, MANIFEST_RELATIVE_PATH).isFile) {
                return candidate
            }
            candidate = candidate.parentFile
        }
        error("Could not locate $MANIFEST_RELATIVE_PATH above ${System.getProperty("user.dir")}")
    }
}
