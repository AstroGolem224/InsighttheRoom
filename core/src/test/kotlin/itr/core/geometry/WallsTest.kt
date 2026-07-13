package itr.core.geometry

import itr.core.model.Wall
import kotlin.test.Test
import kotlin.test.assertEquals

class WallsTest {
    @Test fun `closed polygon yields one wall per edge including closing edge`() {
        val corners = listOf(Vec2(0.0,0.0), Vec2(3.0,0.0), Vec2(3.0,4.0), Vec2(0.0,4.0))
        val walls = wallsFromCorners(corners)
        assertEquals(4, walls.size)
        assertEquals(Wall(Vec2(0.0,0.0), Vec2(3.0,0.0)), walls[0])
        // closing edge back to start
        assertEquals(Wall(Vec2(0.0,4.0), Vec2(0.0,0.0)), walls[3])
    }

    @Test fun `wall length is euclidean`() {
        val w = Wall(Vec2(0.0,0.0), Vec2(3.0,4.0))
        assertEquals(5.0, w.length(), 1e-9)
    }
}
