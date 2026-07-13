package itr.persistence.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "buildings")
data class BuildingEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "rooms",
    foreignKeys = [ForeignKey(
        entity = BuildingEntity::class, parentColumns = ["id"], childColumns = ["buildingId"],
        onDelete = ForeignKey.CASCADE,
    )],
    // v1 invariant: one room per building -> unique buildingId
    indices = [Index(value = ["buildingId"], unique = true)],
)
data class RoomEntity(
    @PrimaryKey val id: String,
    val buildingId: String,
    val name: String,
    val ceilingHeightM: Double?,   // null = unmeasured
    val snapped: Boolean,          // whether the displayed plan used the snapped corners
    val status: String,            // ScanStatus.name — DRAFT | COMPLETE
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "corners",
    foreignKeys = [ForeignKey(entity = RoomEntity::class, parentColumns = ["id"], childColumns = ["roomId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["roomId", "orderIndex"], unique = true)],
)
data class CornerEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val roomId: String,
    val orderIndex: Int,   // 0-based capture order — canonical geometry
    val x: Double,
    val z: Double,
)

@Entity(
    tableName = "snapped_corners",
    foreignKeys = [ForeignKey(entity = RoomEntity::class, parentColumns = ["id"], childColumns = ["roomId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["roomId", "orderIndex"], unique = true)],
)
data class SnappedCornerEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val roomId: String,
    val orderIndex: Int,
    val x: Double,
    val z: Double,
)

@Entity(
    tableName = "room_objects",
    foreignKeys = [ForeignKey(entity = RoomEntity::class, parentColumns = ["id"], childColumns = ["roomId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["roomId", "orderIndex"], unique = true)],
)
data class RoomObjectEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val roomId: String,
    val orderIndex: Int,   // stable, deterministic object order
    val label: String,
    val x: Double,
    val z: Double,
    val confidence: Double,
)
