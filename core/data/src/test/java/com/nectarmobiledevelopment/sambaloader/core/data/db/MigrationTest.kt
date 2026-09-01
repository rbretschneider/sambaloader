package com.nectarmobiledevelopment.sambaloader.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetRepository
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetSource
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetState
import com.nectarmobiledevelopment.sambaloader.core.data.asset.SharedAssetDraft
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Real phones are already carrying a version 1 database with upload
 * history and retention clocks in it, so the upgrade has to preserve
 * every row rather than start clean.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SambaloaderDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `upgrading from version 1 keeps existing assets and marks them camera-roll`() = runTest {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO assets (mediaStoreId, sha256, sizeBytes, capturedAtEpochSeconds, " +
                    "displayName, mimeType, contentUri, state, attemptCount, " +
                    "lastAttemptAtEpochMillis, lastError, uploadedAtEpochMillis) " +
                    "VALUES (42, 'abc123', 2048, 1700000000, 'IMG_0042.jpg', 'image/jpeg', " +
                    "'content://media/42', 'UPLOADED', 0, NULL, NULL, 1700000500000)",
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 2, true, Migrations.MIGRATION_1_2)

        val db = openMigrated()
        try {
            val asset = db.assetDao().byId(42)!!
            assertEquals(AssetSource.MEDIA_STORE, asset.source)
            assertEquals(AssetState.UPLOADED, asset.state)
            assertEquals("abc123", asset.sha256)
            // The retention clock must survive, or D7 would restart it.
            assertEquals(1_700_000_500_000L, asset.uploadedAtEpochMillis)
        } finally {
            db.close()
        }
    }

    @Test
    fun `a migrated database accepts shared assets alongside the camera roll`() = runTest {
        helper.createDatabase(TEST_DB, 1).close()
        helper.runMigrationsAndValidate(TEST_DB, 2, true, Migrations.MIGRATION_1_2)

        val db = openMigrated()
        try {
            val id = AssetRepository(db.assetDao()).addShared(
                SharedAssetDraft(
                    sha256 = "def456",
                    sizeBytes = 100,
                    capturedAtEpochSeconds = 1_700_000_000,
                    displayName = "shared.jpg",
                    mimeType = "image/jpeg",
                    contentUri = "file:///inbox/shared.jpg",
                ),
            )
            assertEquals(AssetSource.SHARED, db.assetDao().byId(id)!!.source)
        } finally {
            db.close()
        }
    }

    private fun openMigrated(): SambaloaderDatabase {
        return Room.databaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            SambaloaderDatabase::class.java,
            TEST_DB,
        ).addMigrations(*Migrations.ALL)
            .allowMainThreadQueries()
            .build()
            .also { helper.closeWhenFinished(it) }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
