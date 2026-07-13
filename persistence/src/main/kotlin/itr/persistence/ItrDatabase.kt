package itr.persistence

import androidx.room.Database
import androidx.room.RoomDatabase
import itr.persistence.entities.*

@Database(
    entities = [BuildingEntity::class, RoomEntity::class, CornerEntity::class, SnappedCornerEntity::class, RoomObjectEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class ItrDatabase : RoomDatabase() {
    abstract fun scanDao(): ScanDao
}
