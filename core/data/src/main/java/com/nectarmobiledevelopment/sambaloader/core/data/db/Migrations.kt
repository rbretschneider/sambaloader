package com.nectarmobiledevelopment.sambaloader.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema history. Version 1 is the pre-release baseline; every version
 * from 2 on must migrate rather than destroy, because by then real phones
 * hold upload history and retention clocks that cannot be rebuilt.
 */
object Migrations {

    /**
     * Adds `source`, distinguishing camera-roll assets from ones handed to
     * the app through the share sheet. Everything that already exists was
     * discovered by scanning, so the default backfills correctly.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE assets ADD COLUMN source TEXT NOT NULL DEFAULT 'MEDIA_STORE'",
            )
        }
    }

    val ALL = arrayOf(MIGRATION_1_2)
}
