package itr.core.geometry

/**
 * Signed vertical room height = (ceiling - floor) projected onto the (normalized) floor
 * normal. SIGNED on purpose: a negative result means the taps were reversed or the normal
 * is mis-oriented — the caller rejects it rather than abs() masking a bad measurement.
 * The caller also applies a plausibility range (e.g. 1.8–4.0 m) before committing.
 */
fun ceilingHeight(floor: Vec3, ceiling: Vec3, normal: Vec3): Double =
    (ceiling - floor).dot(normal.normalized())
