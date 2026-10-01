package info.chrzanowski.ideatmosphere.core

/**
 * Semantic IDE events an [AtmosphereEffect] may react to.
 *
 * Producers (idle tracker, run/debug, build, ...) translate concrete IDE happenings into these
 * generic events so effects never depend on the IDE subsystem that produced them.
 */
enum class AtmosphereEvent {
    /** A long-running activity (build, run, indexing, ...) has started. */
    ACTIVITY_STARTED,

    /** A previously started activity has finished, regardless of outcome. */
    ACTIVITY_FINISHED,

    /** Something succeeded (tests passed, build succeeded, ...). */
    SUCCESS,

    /** Something failed (tests failed, build failed, ...). */
    FAILURE,

    /** The user started interacting with the IDE after being idle. */
    USER_ACTIVE,

    /** The user has not interacted with the IDE for a while. */
    USER_IDLE,

    /**
     * Execution is suspended (the debugger stopped on a breakpoint or after a step): the field holds still almost
     * completely. It ends with [ACTIVITY_STARTED] (still busy) or [ACTIVITY_FINISHED] (back to rest).
     */
    EXECUTION_PAUSED,
}
