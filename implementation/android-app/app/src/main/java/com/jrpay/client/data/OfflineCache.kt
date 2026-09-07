package com.jrpay.client.data

import android.content.Context
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Insert
import androidx.room.OnConflictStrategy

/**
 * UC-G28 — offline read-only cache.
 *
 * Stores the last-fetched JSON payload of "safe" customer surfaces (account
 * list, recent transactions, notifications). Mutating screens (payments,
 * mandates, R2P decisions) deliberately do NOT cache — the customer must be
 * online to push changes.
 *
 * Encryption is supplied by [androidx.security.crypto] via the
 * SecureTokenStore for tokens; the offline cache itself is plain Room — its
 * contents are already redacted server-side (no fødselsnummer, masked
 * holder names, masked IBANs).
 */
class OfflineCache private constructor(private val db: AppDb) {

    suspend fun put(key: String, payload: String) {
        db.snapshots().upsert(SnapshotEntity(key, payload, System.currentTimeMillis()))
    }

    suspend fun get(key: String): SnapshotEntity? = db.snapshots().get(key)

    @Entity(tableName = "snapshots")
    data class SnapshotEntity(
        @PrimaryKey val key: String,
        val payload: String,
        val storedAt: Long,
    )

    @Dao
    interface SnapshotDao {
        @Insert(onConflict = OnConflictStrategy.REPLACE)
        suspend fun upsert(s: SnapshotEntity)

        @Query("SELECT * FROM snapshots WHERE key = :key")
        suspend fun get(key: String): SnapshotEntity?
    }

    @Database(entities = [SnapshotEntity::class], version = 1, exportSchema = false)
    abstract class AppDb : RoomDatabase() {
        abstract fun snapshots(): SnapshotDao
    }

    companion object {
        fun create(context: Context): OfflineCache {
            val db = Room.databaseBuilder(
                context.applicationContext,
                AppDb::class.java,
                "jrpay-cache.db",
            ).build()
            return OfflineCache(db)
        }
    }
}
