package com.example.echo

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ================= 1. 数据库表结构 (Entity) =================

@Entity(tableName = "maps")
data class MapEntity(
    @PrimaryKey val mapId: String,
    val mapName: String,
    val createdAt: Long,
    val width: Double,
    val length: Double,
    val polygonBounds: String = "",
    val isArScanned: Boolean = false,
    val bgImageUri: String = "",         // 底图本地文件路径（filesDir/maps/{mapId}/bg.jpg），空字符串表示无底图
    val bgOffsetX: Double = 0.0,         // 底图相对地图原点的水平偏移（米）
    val bgOffsetY: Double = 0.0          // 底图相对地图原点的垂直偏移（米）
)

@Entity(
    tableName = "fingerprints",
    foreignKeys = [
        ForeignKey(
            entity = MapEntity::class,
            parentColumns = ["mapId"],
            childColumns = ["mapId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["mapId"])]
)
data class ReferencePointEntity(
    @PrimaryKey val id: String,
    val mapId: String,
    val x: Double,
    val y: Double,
    val fingerprintData: String
)

@Entity(
    tableName = "obstacles",
    foreignKeys = [
        ForeignKey(
            entity = MapEntity::class,
            parentColumns = ["mapId"],
            childColumns = ["mapId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["mapId"])]
)
data class ObstacleEntity(
    @PrimaryKey val id: String,
    val mapId: String,
    val x: Double,
    val y: Double
)

// ================= 2. 类型转换器 (TypeConverter) =================
class Converters {
    @TypeConverter
    fun fromFingerprintMap(map: Map<String, Int>): String {
        return map.entries.joinToString(";") { "${it.key}=${it.value}" }
    }

    @TypeConverter
    fun toFingerprintMap(data: String): Map<String, Int> {
        if (data.isBlank()) return emptyMap()
        return data.split(";").mapNotNull {
            val pair = it.split("=")
            if (pair.size == 2) pair[0] to (pair[1].toIntOrNull() ?: -100) else null
        }.toMap()
    }

    @TypeConverter
    fun fromPointList(points: List<Point>?): String {
        if (points.isNullOrEmpty()) return ""
        return points.joinToString("|") { "${it.x},${it.y}" }
    }

    @TypeConverter
    fun toPointList(data: String): List<Point> {
        if (data.isBlank()) return emptyList()
        return data.split("|").mapNotNull {
            val coords = it.split(",")
            if (coords.size == 2) {
                val x = coords[0].toDoubleOrNull()
                val y = coords[1].toDoubleOrNull()
                if (x != null && y != null) Point(x, y) else null
            } else null
        }
    }
}

// ================= 数据模型映射 (Mapping) =================
fun ReferencePoint.toEntity(mapId: String): ReferencePointEntity {
    return ReferencePointEntity(
        id = this.id,
        mapId = mapId,
        x = this.coordinate.x,
        y = this.coordinate.y,
        fingerprintData = Converters().fromFingerprintMap(this.fingerprint)
    )
}

fun ReferencePointEntity.toDomainModel(): ReferencePoint {
    return ReferencePoint(
        id = this.id,
        coordinate = Point(this.x, this.y),
        fingerprint = Converters().toFingerprintMap(this.fingerprintData)
    )
}

// ================= 3. 数据访问接口 (DAO) =================
// 🚀 终极修复：去掉了所有的 suspend 关键字，回归大道至简！
// 因为我们在 ViewModel 里已经用 Dispatchers.IO 包装过了，主线程绝对安全。
@Dao
interface MapDao {
    @Query("SELECT * FROM maps ORDER BY createdAt DESC")
    fun getAllMapsStream(): Flow<List<MapEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertMap(map: MapEntity)

    @Delete
    fun deleteMap(map: MapEntity)
}

@Dao
interface FingerprintDao {
    @Query("SELECT * FROM fingerprints WHERE mapId = :mapId")
    fun getFingerprintsByMapStream(mapId: String): Flow<List<ReferencePointEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertFingerprint(point: ReferencePointEntity)

    @Delete
    fun deleteFingerprint(point: ReferencePointEntity)

    @Query("DELETE FROM fingerprints WHERE mapId = :mapId")
    fun clearAllInMap(mapId: String)
}

@Dao
interface ObstacleDao {
    @Query("SELECT * FROM obstacles WHERE mapId = :mapId")
    fun getObstaclesByMapStream(mapId: String): Flow<List<ObstacleEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertObstacle(obstacle: ObstacleEntity)

    @Delete
    fun deleteObstacle(obstacle: ObstacleEntity)

    @Query("DELETE FROM obstacles WHERE mapId = :mapId")
    fun clearAllObstaclesInMap(mapId: String)
}

// ================= 4. 数据库引擎单例 =================
@Database(
    entities = [MapEntity::class, ReferencePointEntity::class, ObstacleEntity::class],
    version = 5,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mapDao(): MapDao
    abstract fun fingerprintDao(): FingerprintDao
    abstract fun obstacleDao(): ObstacleDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "indoor_navi_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}