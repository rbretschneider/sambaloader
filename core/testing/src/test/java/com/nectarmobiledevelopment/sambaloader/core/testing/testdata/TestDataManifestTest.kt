package com.nectarmobiledevelopment.sambaloader.core.testing.testdata

import com.nectarmobiledevelopment.sambaloader.core.crypto.Sha256
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Guards the seed corpus: if a testdata file or the manifest drifts, every
 * downstream test that trusts these hashes would silently rot — fail here
 * first, loudly.
 */
class TestDataManifestTest {

    @Test
    fun `manifest is non-empty and includes a unicode-named asset`() {
        val manifest = TestData.manifest()
        assertTrue(manifest.isNotEmpty(), "manifest.json has no entries")
        assertTrue(
            manifest.any { asset -> asset.name.any { it.code > 127 } },
            "expected at least one non-ASCII filename in the corpus",
        )
    }

    @Test
    fun `every manifest entry matches its file on disk`() {
        for (asset in TestData.manifest()) {
            val file = TestData.file(asset)
            assertTrue(file.isFile, "missing testdata file: ${asset.name}")
            assertEquals(asset.sizeBytes, file.length(), "size drift: ${asset.name}")
            val actualHash = file.inputStream().use { Sha256.hex(it) }
            assertEquals(asset.sha256, actualHash, "hash drift: ${asset.name}")
        }
    }
}
