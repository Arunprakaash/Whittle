package app.whittle.core.common

/** Source of wall-clock time, injectable for tests. */
fun interface EpochClock {
    fun nowMillis(): Long

    companion object {
        val System: EpochClock = EpochClock { java.lang.System.currentTimeMillis() }
    }
}
