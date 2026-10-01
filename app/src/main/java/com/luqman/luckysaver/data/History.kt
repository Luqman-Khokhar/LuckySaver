package com.luqman.luckysaver.data

import android.content.Context
import androidx.room.ColumnInfo
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
    /** Saved by a scheduled story check rather than by the user. */
    @ColumnInfo(defaultValue = "0") val fromWatchlist: Boolean = false,
    @ColumnInfo(defaultValue = "0") val isStory: Boolean = false,
    /** When Instagram says it was posted, epoch millis; 0 when unknown. */
    @ColumnInfo(defaultValue = "0") val takenAt: Long = 0,
    /**
     * The file is gone, deleted in the app or elsewhere. The row stays so the same item is never
     * downloaded again, which matters for stories that are still live when they're deleted.
     */
    @ColumnInfo(defaultValue = "0") val removed: Boolean = false,
)

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads WHERE removed = 0 ORDER BY savedAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE fromWatchlist = 1 AND removed = 0 ORDER BY savedAt DESC")
    fun observeWatchlist(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE removed = 0")
    suspend fun present(): List<DownloadEntity>

    @Query("UPDATE downloads SET owner = :owner WHERE key = :key AND owner != :owner")
    suspend fun correctOwner(key: String, owner: String)

    @Query("UPDATE downloads SET removed = 1 WHERE key IN (:keys)")
    suspend fun markRemoved(keys: List<String>)

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
    /** Instagram CDN link, signed and short-lived; refreshed on every story check. */
    val avatarUrl: String? = null,
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

    @Query("UPDATE watched_accounts SET avatarUrl = :url WHERE userId = :userId")
    suspend fun updateAvatar(userId: String, url: String)
}

@Database(entities = [DownloadEntity::class, WatchedAccount::class], version = 4, exportSchema = false)
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

        /**
         * Marks which downloads the watchlist made. Rows saved before this have no flag, so the
         * best guess is anything from a watched account after it was added.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN fromWatchlist INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    UPDATE downloads SET fromWatchlist = 1 WHERE EXISTS (
                        SELECT 1 FROM watched_accounts w
                        WHERE lower(w.username) = lower(downloads.owner) AND downloads.savedAt >= w.addedAt
                    )
                    """.trimIndent()
                )
            }
        }

        /** Story and posted-time flags for the library, soft delete, and account pictures. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN isStory INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE downloads ADD COLUMN takenAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE downloads ADD COLUMN removed INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE downloads SET isStory = 1 WHERE fromWatchlist = 1")
                db.execSQL("ALTER TABLE watched_accounts ADD COLUMN avatarUrl TEXT")
            }
        }

        fun build(context: Context) =
            Room.databaseBuilder(context, AppDatabase::class.java, "luckysaver.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}
