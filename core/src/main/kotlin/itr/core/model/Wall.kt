package itr.core.model

import itr.core.geometry.Vec2

data class Wall(val from: Vec2, val to: Vec2) {
    fun length() = (to - from).length()
}
