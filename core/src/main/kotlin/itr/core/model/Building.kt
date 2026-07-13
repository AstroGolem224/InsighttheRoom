package itr.core.model

/** A building groups rooms. v1 always stores exactly one room; the type is multi-room-ready. */
data class Building(
    val id: String,
    val name: String,
    val rooms: List<ScannedRoom>,
    val createdAtEpochMs: Long,
)
