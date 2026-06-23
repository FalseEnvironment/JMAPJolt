package com.falseenvironment.jmapjolt.cache

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase

/**
 * One cached email row. Rows are grouped by [bucket] — a string identifying the
 * account + folder snapshot they belong to (e.g. "me@example.com#2131296123").
 * Attachments and labels are stored as JSON blobs to keep the schema flat.
 */
@Entity(tableName = "cached_emails", primaryKeys = ["bucket", "id"])
data class CachedEmailRow(
    val bucket: String,
    val id: String,
    val subject: String,
    val from: String,
    @ColumnInfo(name = "from_email") val fromEmail: String,
    val preview: String,
    @ColumnInfo(name = "full_body") val fullBody: String,
    val seen: Boolean,
    @ColumnInfo(name = "is_favorite") val isFavorite: Boolean,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
    @ColumnInfo(name = "to_email") val toEmail: String,
    @ColumnInfo(name = "account_email") val accountEmail: String,
    @ColumnInfo(name = "attachments_json") val attachmentsJson: String,
    @ColumnInfo(name = "labels_json") val labelsJson: String
)

@Dao
interface CachedEmailDao {
    @Query("SELECT * FROM cached_emails WHERE bucket = :bucket ORDER BY received_at DESC")
    suspend fun loadBucket(bucket: String): List<CachedEmailRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rows: List<CachedEmailRow>)

    @Query("DELETE FROM cached_emails WHERE bucket = :bucket")
    suspend fun clearBucket(bucket: String)
}

@Database(entities = [CachedEmailRow::class], version = 1, exportSchema = false)
abstract class EmailCacheDatabase : RoomDatabase() {
    abstract fun cachedEmailDao(): CachedEmailDao
}
