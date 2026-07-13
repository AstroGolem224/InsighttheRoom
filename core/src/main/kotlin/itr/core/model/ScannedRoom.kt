package itr.core.model

/**
 * One scanned room: its floorplan plus metadata. [ceilingHeightM] is null when unmeasured
 * (never a silent default). [status] distinguishes a recoverable draft from a complete scan.
 * [floorPlan] holds derived walls/area; only its inputs are persisted.
 */
data class ScannedRoom(
    val id: String,
    val name: String,
    val floorPlan: FloorPlan,
    val ceilingHeightM: Double?,
    val status: ScanStatus,
    val createdAtEpochMs: Long,
)
