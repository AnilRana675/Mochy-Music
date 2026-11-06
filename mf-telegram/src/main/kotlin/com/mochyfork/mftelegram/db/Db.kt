// our own room database for the channel index and confirmed matches. never touches upstream
// entities or migrations; this file holds the whole schema on purpose (four small tables)
package com.mochyfork.mftelegram.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

// a channel the user picked for the index
@Entity(tableName = "channels")
data class ChannelEntity(
    @PrimaryKey val chatId: Long,
    val title: String,
    val username: String?,
    // lowest message id up to which history was fully scanned; 0 means nothing scanned yet
    @ColumnInfo(name = "scanned_to_id") val scannedToId: Long = 0L,
    @ColumnInfo(name = "track_count") val trackCount: Int = 0,
    // still looksupable: cleared when the channel is left or kicked
    val active: Boolean = true,
    val updatedAt: Long = 0L,
)

// one audio file seen in a channel. uniqueId (tdlib remote id) is the stable key; fileId can
// change after restart and is re-resolved when a download needs it
@Entity(
    tableName = "tracks",
    indices = [Index(value = ["chatId", "messageId"], unique = true)],
)
data class TrackEntity(
    @PrimaryKey @ColumnInfo(name = "unique_id") val uniqueId: String,
    val chatId: Long,
    val messageId: Long,
    @ColumnInfo(name = "file_id") val fileId: Long,
    @ColumnInfo(name = "file_name") val fileName: String?,
    val title: String,
    val artist: String,
    val album: String?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long = 0L,
    val mime: String?,
    // where title/artist came from: "tag", "name" or "caption"
    val source: String,
    val indexedAt: Long,
)

// a confirmed song -> telegram file link, so the next play of the song is instant. tokens are the
// song keys the player passes us (its own key space), never edited upstream
@Entity(tableName = "matches")
data class MatchEntity(
    // the song key the player passes us (youtube media id today)
    @PrimaryKey val token: String,
    @ColumnInfo(name = "unique_id") val uniqueId: String,
    val chatId: Long,
    val confirmedAt: Long,
    val confidence: Float,
    // blanked from a user's manual link; a manual match is never auto-refuted
    val manual: Boolean = false,
    // failed telegram attempts for this match, drives the skip-backoff
    @ColumnInfo(name = "failures") val failures: Int = 0,
    @ColumnInfo(name = "backoff_until") val backoffUntil: Long = 0L,
)

@Dao
interface TelegramDao {
    // channels

    @Upsert
    suspend fun upsertChannel(channel: ChannelEntity)

    @Query("SELECT * FROM channels ORDER BY updatedAt DESC")
    suspend fun channels(): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE active = 1")
    fun activeChannels(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE chatId = :chatId")
    suspend fun channel(chatId: Long): ChannelEntity?

    @Query("UPDATE channels SET active = 0 WHERE chatId = :chatId")
    suspend fun deactivate(chatId: Long)

    @Query("DELETE FROM channels WHERE chatId = :chatId")
    suspend fun forgetChannel(chatId: Long)

    // tracks

    @Upsert
    suspend fun upsertTrack(track: TrackEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTracks(tracks: List<TrackEntity>)

    @Query("DELETE FROM tracks WHERE chatId = :chatId AND messageId = :messageId")
    suspend fun deleteTrack(chatId: Long, messageId: Long)

    @Query("DELETE FROM tracks WHERE chatId = :chatId")
    suspend fun deleteTracksOf(chatId: Long)

    @Query("SELECT COUNT(*) FROM tracks WHERE chatId = :chatId")
    suspend fun trackCount(chatId: Long): Int

    @Query("SELECT * FROM tracks WHERE unique_id = :uniqueId")
    suspend fun track(uniqueId: String): TrackEntity?

    // every track of every active channel, for the matcher. small channel libraries only;
    // if this ever gets big, paging on (chatId, messageId) is the upgrade
    @Query(
        "SELECT tracks.* FROM tracks JOIN channels ON channels.chatId = tracks.chatId " +
            "WHERE channels.active = 1",
    )
    suspend fun indexedTracks(): List<TrackEntity>

    // matches

    @Upsert
    suspend fun upsertMatch(match: MatchEntity)

    @Query("SELECT * FROM matches WHERE token = :token")
    suspend fun match(token: String): MatchEntity?

    @Query("SELECT * FROM matches WHERE token = :token")
    fun matchFlow(token: String): Flow<MatchEntity?>

    @Query("DELETE FROM matches WHERE unique_id = :uniqueId")
    suspend fun dropMatchesFor(uniqueId: String)

    @Query("UPDATE matches SET failures = failures + 1, backoff_until = :until WHERE token = :token")
    suspend fun noteFailure(token: String, until: Long)

    @Query("UPDATE matches SET failures = 0, backoff_until = 0 WHERE token = :token")
    suspend fun clearFailures(token: String)
}

@Database(
    entities = [ChannelEntity::class, TrackEntity::class, MatchEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class TelegramDb : RoomDatabase() {
    abstract fun dao(): TelegramDao
}
