package itr.core.ar

/**
 * Assigns a stable string id per DISTINCT plane by the plane's OWN equality (ARCore Plane.equals =
 * native handle), so different Java wrappers of the same handle share an id — required for
 * resolveRoot/subsumption to compare correctly. One registry per AR session; single-threaded by
 * contract (the AR frame thread). NOT IdentityHashMap: that would give same-handle wrappers different ids.
 */
class PlaneRegistry {
    private val ids = HashMap<Any, String>()   // equality-keyed
    private var counter = 0L
    fun idFor(plane: Any): String = ids.getOrPut(plane) { "plane-${counter++}" }
}
