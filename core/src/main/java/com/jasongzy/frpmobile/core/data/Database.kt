package com.jasongzy.frpmobile.core.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.Index
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Transaction
import androidx.room.ColumnInfo
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.jasongzy.frpmobile.core.frp.FrpInstanceStatus
import com.jasongzy.frpmobile.core.frp.FrpLog
import com.jasongzy.frpmobile.core.frp.FrpProfile
import com.jasongzy.frpmobile.core.frp.FrpRuntimeState
import com.jasongzy.frpmobile.core.frp.FrpType
import com.jasongzy.frpmobile.core.frp.ThemeMode
import com.jasongzy.frpmobile.core.frp.LanguageMode
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "profiles")
data class FrpProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: FrpType,
    val toml: String,
    val autoStart: Boolean,
    val updatedAt: Long,
) {
    fun toModel(): FrpProfile = FrpProfile(id, name, type, toml, autoStart, updatedAt)
}

@Entity(tableName = "certificates", primaryKeys = ["profileId", "name"])
data class CertificateEntity(val profileId: String, val name: String, val content: String)

@Entity(tableName = "runtime_states")
data class FrpRuntimeStateEntity(
    @PrimaryKey val id: String,
    val type: FrpType,
    val state: FrpInstanceStatus,
    val lastError: String?,
    val desiredRunning: Boolean = false,
    @ColumnInfo(defaultValue = "''") val runToken: String = "",
) {
    fun toModel(): FrpRuntimeState = FrpRuntimeState(id, type, state, lastError, desiredRunning, runToken)
}

@Entity(
    tableName = "logs",
    indices = [
        Index(value = ["time"]),
        Index(value = ["level"]),
        Index(value = ["type"]),
        Index(value = ["instanceId"]),
    ],
)
data class FrpLogEntity(
    @PrimaryKey(autoGenerate = true) val uid: Long = 0,
    val instanceId: String,
    val type: String,
    val level: String,
    val message: String,
    val time: Long,
) {
    fun toModel(): FrpLog = FrpLog(
        instanceId = instanceId,
        type = type,
        level = level,
        message = message,
        time = time,
        uid = uid,
    )
}

class FrpTypeConverters {
    @TypeConverter fun themeToString(value: ThemeMode): String = value.name
    @TypeConverter fun stringToTheme(value: String): ThemeMode = ThemeMode.valueOf(value)
    @TypeConverter fun languageToString(value: LanguageMode): String = value.name
    @TypeConverter fun stringToLanguage(value: String): LanguageMode = LanguageMode.valueOf(value)
    @TypeConverter fun typeToString(type: FrpType): String = type.name
    @TypeConverter fun stringToType(value: String): FrpType = FrpType.valueOf(value)
    @TypeConverter fun statusToString(status: FrpInstanceStatus): String = status.name
    @TypeConverter fun stringToStatus(value: String): FrpInstanceStatus = FrpInstanceStatus.valueOf(value)
}

@Dao
interface FrpDao {
    @Query("SELECT * FROM settings WHERE id = 0")
    fun observeSettings(): Flow<FrpSettings?>

    @Query("SELECT * FROM settings WHERE id = 0")
    suspend fun getSettings(): FrpSettings?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSettings(settings: FrpSettings)

    @Query("SELECT * FROM certificates WHERE profileId = :id")
    suspend fun getCertificates(id: String): List<CertificateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCertificate(certificate: CertificateEntity)

    @Query("DELETE FROM certificates WHERE profileId = :id")
    suspend fun deleteCertificates(id: String)

    @Query("SELECT * FROM profiles ORDER BY updatedAt DESC, id ASC")
    suspend fun getProfiles(): List<FrpProfileEntity>

    @Transaction
    suspend fun restoreProfiles(profiles: List<FrpProfileEntity>, certificates: List<CertificateEntity>, replace: Boolean, settings: FrpSettings) {
        if (replace) getProfiles().forEach {
            deleteProfile(it.id)
            deleteCertificates(it.id)
            deleteRuntimeState(it.id)
        }
        profiles.forEach {
            upsertProfile(it)
            deleteCertificates(it.id)
            deleteRuntimeState(it.id)
        }
        certificates.forEach { upsertCertificate(it) }
        getRuntimeStates().forEach {
            upsertRuntimeState(it.copy(state = FrpInstanceStatus.Stopped, desiredRunning = false, lastError = null))
        }
        upsertSettings(settings)
    }

    @Transaction
    suspend fun deleteConfiguration(id: String) {
        deleteProfile(id)
        deleteCertificates(id)
        deleteRuntimeState(id)
    }

    @Transaction
    suspend fun createConfiguration(profile: FrpProfileEntity, certificates: List<CertificateEntity>) {
        insertProfile(profile)
        certificates.forEach { upsertCertificate(it) }
    }

    @Transaction
    suspend fun saveConfiguration(profile: FrpProfileEntity, certificates: List<CertificateEntity>?) {
        upsertProfile(profile)
        if (certificates != null) {
            deleteCertificates(profile.id)
            certificates.forEach { upsertCertificate(it) }
        }
    }

    @Query("SELECT * FROM profiles ORDER BY updatedAt DESC, id ASC")
    fun observeProfiles(): Flow<List<FrpProfileEntity>>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun getProfile(id: String): FrpProfileEntity?

    @Query("SELECT * FROM profiles WHERE autoStart = 1")
    suspend fun getAutoStartProfiles(): List<FrpProfileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: FrpProfileEntity)

    @Insert
    suspend fun insertProfile(profile: FrpProfileEntity)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun deleteProfile(id: String)

    @Query("SELECT * FROM runtime_states")
    fun observeRuntimeStates(): Flow<List<FrpRuntimeStateEntity>>

    @Query("SELECT * FROM runtime_states")
    suspend fun getRuntimeStates(): List<FrpRuntimeStateEntity>

    @Query("SELECT * FROM runtime_states WHERE desiredRunning = 1")
    suspend fun getDesiredRunningStates(): List<FrpRuntimeStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRuntimeState(state: FrpRuntimeStateEntity)

    @Query("DELETE FROM runtime_states WHERE id = :id")
    suspend fun deleteRuntimeState(id: String)

    @Query(
        """
        SELECT * FROM logs
        WHERE (:instanceId IS NULL OR instanceId = :instanceId)
        AND (:type IS NULL OR type = :type)
        AND (:level IS NULL OR level = :level)
        AND (:keyword IS NULL OR message LIKE '%' || :keyword || '%')
        AND (:maxUid IS NULL OR uid <= :maxUid)
        ORDER BY time DESC, uid DESC
        LIMIT :limit
        """,
    )
    fun observeLogs(
        instanceId: String?,
        type: String?,
        level: String?,
        keyword: String?,
        limit: Int,
        maxUid: Long?,
    ): Flow<List<FrpLogEntity>>

    @Query("SELECT COALESCE(MAX(uid), 0) FROM logs")
    suspend fun latestLogId(): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLogs(logs: List<FrpLogEntity>)

    @Query(
        """
        DELETE FROM logs
        WHERE uid <= COALESCE(
            (
                SELECT uid FROM logs
                ORDER BY uid DESC
                LIMIT 1 OFFSET :maxCount
            ),
            -1
        )
        """,
    )
    suspend fun trimLogs(maxCount: Int)

    @Query("DELETE FROM logs WHERE time < :olderThan")
    suspend fun deleteLogsOlderThan(olderThan: Long)

    @Query("DELETE FROM logs")
    suspend fun clearLogs()

    @Query("SELECT MIN(time) FROM logs")
    fun observeOldestLogTime(): Flow<Long?>

}

@Database(
    entities = [FrpSettings::class, FrpProfileEntity::class, CertificateEntity::class, FrpRuntimeStateEntity::class, FrpLogEntity::class],
    version = 4,
    exportSchema = false,
)
@TypeConverters(FrpTypeConverters::class)
abstract class FrpDatabase : RoomDatabase() {
    abstract fun frpDao(): FrpDao

    companion object {
        @Volatile private var instance: FrpDatabase? = null

        fun get(context: Context): FrpDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    FrpDatabase::class.java,
                    "frp-mobile.db",
                ).addMigrations(object : Migration(3, 4) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE settings ADD COLUMN liveUpdatesEnabled INTEGER NOT NULL DEFAULT 0")
                    }
                }).build().also { instance = it }
            }
    }
}

fun FrpProfile.toEntity(): FrpProfileEntity =
    FrpProfileEntity(id, name, type, toml, autoStart, updatedAt)

fun FrpLog.toEntity(): FrpLogEntity =
    FrpLogEntity(uid = uid, instanceId = instanceId, type = type, level = level, message = message, time = time)
