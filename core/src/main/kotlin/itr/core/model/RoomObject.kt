package itr.core.model

import itr.core.geometry.Vec2

/** A furniture marker placed on the floor, room-local coordinates. */
data class RoomObject(val label: String, val position: Vec2, val confidence: Double)
