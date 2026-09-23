package com.luqman.luckysaver.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val key: String,
    val owner: String,
    val shortcode: String?,
    val isVideo: Boolean,
    val uri: String,
    val fileName: String,
    val savedAt: Long,
)

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY savedAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT key FROM downloads WHERE key IN (:keys)")
    suspend fun existingKeys(keys: List<String>): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DownloadEntity)

    @Query("DELETE FROM downloads WHERE key = :key")
    suspend fun delete(key: String)
}

@Entity(tableName = "watched_accounts")
data class WatchedAccount(
    /** Instagram's numeric id: usernames change, this doesn't. */
    @PrimaryKey val userId: String,
    val username: String,
    val enabled: Boolean = true,
    val addedAt: Long,
    val lastCheckedAt: Long = 0,
    val savedCount: Int = 0,
)

@Dao
interface WatchedAccountDao {
    @Query("SELECT * FROM watched_accounts ORDER BY username")
    fun observeAll(): Flow<List<WatchedAccount>>

    @Query("SELECT * FROM watched_accounts WHERE enabled = 1")
    suspend fun enabled(): List<WatchedAccount>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: WatchedAccount)

    @Query("DELETE FROM watched_accounts WHERE userId = :userId")
    suspend fun delete(userId: String)

    @Query("UPDATE watched_accounts SET enabled = :enabled WHERE userId = :userId")
    suspend fun setEnabled(userId: String, enabled: Boolean)

    @Query("UPDATE watched_accounts SET lastCheckedAt = :at, savedCount = savedCount + :saved WHERE userId = :userId")
    suspend fun recordCheck(userId: String, at: Long, saved: Int)
}

@Database(entities = [DownloadEntity::class, WatchedAccount::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloads(): DownloadDao
    abstract fun watched(): WatchedAccountDao

    companion object {
        /** Adds the watchlist. Download history is kept, so no destructive fallback. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS watched_accounts (
                        userId TEXT NOT NULL PRIMARY KEY,
                        username TEXT NOT NULL,
                        enabled INTEGER NOT NULL,
                        addedAt INTEGER NOT NULL,
                        lastCheckedAt INTEGER NOT NULL,
                        savedCount INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        fun build(context: Context) =
            Room.databaseBuilder(context, AppDatabase::class.java, "luckysaver.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
