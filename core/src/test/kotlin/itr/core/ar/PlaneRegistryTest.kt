package itr.core.ar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PlaneRegistryTest {
    // Model an ARCore Plane: equals/hashCode identify the native handle; distinct wrappers of the
    // same handle are .equals()-equal.
    private class Handle(val id: Int) { override fun equals(o: Any?) = o is Handle && o.id == id; override fun hashCode() = id }

    @Test fun `distinct wrappers of the SAME handle get the SAME id (equality identity)`() {
        val r = PlaneRegistry()
        assertEquals(r.idFor(Handle(7)), r.idFor(Handle(7)))   // two wrappers, same native handle
    }

    @Test fun `different handles get different ids`() {
        val r = PlaneRegistry()
        assertNotEquals(r.idFor(Handle(1)), r.idFor(Handle(2)))
    }

    // hashCode collision but NOT equal -> must still get different ids (equality, not hashCode, decides)
    private class Colliding(val n: Int) { override fun equals(o: Any?) = o is Colliding && o.n == n; override fun hashCode() = 0 }

    @Test fun `hashCode-colliding but unequal handles get different ids`() {
        val r = PlaneRegistry()
        assertNotEquals(r.idFor(Colliding(1)), r.idFor(Colliding(2)))   // same hashCode 0, different equals
    }

    @Test fun `ids are stable across interleaved lookups`() {
        val r = PlaneRegistry()
        val a = r.idFor(Handle(1)); r.idFor(Handle(2))
        assertEquals(a, r.idFor(Handle(1)))
    }
}
