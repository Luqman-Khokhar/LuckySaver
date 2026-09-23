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

@Database(entities = [DownloadEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloads(): DownloadDao

    companion object {
        fun build(context: Context) =
            Room.databaseBuilder(context, AppDatabase::class.java, "luckysaver.db").build()
    }
}
