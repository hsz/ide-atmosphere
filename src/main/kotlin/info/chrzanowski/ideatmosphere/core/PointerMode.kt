package info.chrzanowski.ideatmosphere.core

/**
 * How particles react to the mouse pointer. The pointer is only ever observed; no mode receives or consumes input.
 */
enum class PointerMode {
    /** Particles glide away from the pointer. */
    AVOID,

    /** Particles drift towards the pointer and hover around it instead of piling up on top of it. */
    ATTRACT,

    /** The pointer is ignored entirely; clicks do not puff particles either. */
    OFF,
}
