package com.nectarmobiledevelopment.sambaloader.core.data.scan

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nectarmobiledevelopment.sambaloader.core.data.db.SambaloaderDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ScanCursorRepositoryTest {

    private lateinit var db: SambaloaderDatabase
    private lateinit var repository: ScanCursorRepository

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, SambaloaderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ScanCursorRepository(db.scanCursorDao())
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun `starts at zero so the first scan is a full backfill`() = runTest {
        val cursor = repository.current()
        assertEquals(0L, cursor.lastDateAddedEpochSeconds)
        assertNull(cursor.lastGeneration)
    }

    @Test
    fun `advance persists and overwrites the single row`() = runTest {
        repository.advance(lastDateAddedEpochSeconds = 100, generation = 5)
        repository.advance(lastDateAddedEpochSeconds = 200, generation = 6)

        val cursor = repository.current()
        assertEquals(200L, cursor.lastDateAddedEpochSeconds)
        assertEquals(6L, cursor.lastGeneration)
    }
}
