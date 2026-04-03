package com.pixelhunter.cam.db

import android.content.Context
import androidx.annotation.WorkerThread
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

// ─── Entities ─────────────────────────────────────────────────────

@Entity(tableName = "shoot_locations")
data class ShootLocation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lat: Double,
    val lng: Double,
    val label: String,           // User-set or auto-generated ("Location #4")
    val createdAt: Long,
    val thumbnailPath: String,   // Path to representative thumbnail

    // Settings that worked well here — suggested on return
    val lastIso: Int = 0,
    val lastShutterNs: Long = 0L,
    val lastWhiteBalanceK: Int = 0,
    val lastExposureComp: Int = 0
)

@Entity(
    tableName = "shoot_images",
    foreignKeys = [ForeignKey(
        entity = ShootLocation::class,
        parentColumns = ["id"],
        childColumns = ["locationId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("locationId")]
)
data class ShootImage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val locationId: Long,
    val imagePath: String,
    val thumbnailPath: String,
    val capturedAt: Long,

    // Analysis results stored with each image
    val blurScore: Double,
    val luminance: Float,
    val colorTempK: Float,
    val hadFlags: Boolean,
    val flagTypes: String,       // Comma-separated FlagType names

    // Settings used when this shot was taken
    val iso: Int,
    val shutterNs: Long,
    val whiteBalanceK: Int
)

// ─── DAOs ─────────────────────────────────────────────────────────

@Dao
interface ShootLocationDao {

    @WorkerThread
    @Query("SELECT * FROM shoot_locations ORDER BY createdAt DESC")
    fun getAllLocations(): Flow<List<ShootLocation>>

    @WorkerThread
    @Query("""
        SELECT * FROM shoot_locations 
        WHERE ABS(lat - :lat) < :latDelta AND ABS(lng - :lng) < :lngDelta
        ORDER BY createdAt DESC
        LIMIT 20
    """)
    suspend fun findNearby(lat: Double, lng: Double, latDelta: Double, lngDelta: Double): List<ShootLocation>

    @WorkerThread
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(location: ShootLocation): Long

    @WorkerThread
    @Update
    suspend fun update(location: ShootLocation)

    @WorkerThread
    @Delete
    suspend fun delete(location: ShootLocation)

    @WorkerThread
    @Query("SELECT * FROM shoot_locations WHERE id = :id")
    suspend fun getById(id: Long): ShootLocation?
}

@Dao
interface ShootImageDao {

    @WorkerThread
    @Query("SELECT * FROM shoot_images WHERE locationId = :locationId ORDER BY capturedAt DESC")
    fun getImagesForLocation(locationId: Long): Flow<List<ShootImage>>

    @WorkerThread
    @Query("SELECT * FROM shoot_images WHERE locationId = :locationId ORDER BY capturedAt DESC LIMIT 20")
    suspend fun getRecentForLocation(locationId: Long): List<ShootImage>

    @WorkerThread
    @Query("SELECT * FROM shoot_images ORDER BY capturedAt DESC")
    suspend fun getAllImages(): List<ShootImage>

    @WorkerThread
    @Query("SELECT * FROM shoot_images WHERE imagePath = :imagePath LIMIT 1")
    suspend fun getImageByPath(imagePath: String): ShootImage?

    @WorkerThread
    @Insert
    suspend fun insert(image: ShootImage): Long

    @WorkerThread
    @Query("DELETE FROM shoot_images WHERE id = :id")
    suspend fun deleteById(id: Long)

    @WorkerThread
    @Delete
    suspend fun deleteImage(image: ShootImage)

    @WorkerThread
    @Query("SELECT COUNT(*) FROM shoot_images WHERE locationId = :locationId")
    suspend fun countForLocation(locationId: Long): Int

    @WorkerThread
    @Query("SELECT locationId, COUNT(*) as count FROM shoot_images WHERE locationId IN (:locationIds) GROUP BY locationId")
    suspend fun countForLocations(locationIds: List<Long>): List<LocationImageCount>
}

data class LocationImageCount(val locationId: Long, val count: Int)

// ─── Database ─────────────────────────────────────────────────────

@Database(
    entities = [ShootLocation::class, ShootImage::class],
    version = 1,
    exportSchema = true  // Changed from false — export schema for migration validation
)
abstract class PixelHunterDatabase : RoomDatabase() {
    abstract fun locationDao(): ShootLocationDao
    abstract fun imageDao(): ShootImageDao

    companion object {
        @Volatile private var INSTANCE: PixelHunterDatabase? = null

        // Migration stub — add entries here as schema evolves.
        // Example for v1→v2:
        //   val MIGRATION_1_2 = object : Migration(1, 2) {
        //       override fun migrate(db: SupportSQLiteDatabase) {
        //           db.execSQL("ALTER TABLE shoot_images ADD COLUMN faceCount INTEGER NOT NULL DEFAULT 0")
        //       }
        //   }

        fun getInstance(context: Context): PixelHunterDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    PixelHunterDatabase::class.java,
                    "pixelhunter.db"
                )
                // Add migrations here: .addMigrations(MIGRATION_1_2)
                // fallbackToDestructiveMigration() is intentionally NOT set —
                // losing user data is never acceptable even during development.
                .build().also { INSTANCE = it }
            }
        }
    }
}
