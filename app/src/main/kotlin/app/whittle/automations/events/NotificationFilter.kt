package app.whittle.automations.events

/** Which posted notifications count as an event. */
object NotificationFilter {
    /** How many notifications' last text is remembered; the oldest go first so the map can't grow for ever. */
    const val MAX_KEYS = 200

    /**
     * Not Whittle's own, not ongoing ones (music, calls, downloads) and not group summaries. An app re-posts a
     * notification to update it, so the same key with the same text fires once; [lastTextByKey] remembers the text
     * (in memory only, owned by the caller).
     */
    fun shouldFire(
        packageName: String,
        ownPackage: String,
        ongoing: Boolean,
        groupSummary: Boolean,
        key: String,
        text: String,
        lastTextByKey: MutableMap<String, String>,
    ): Boolean {
        if (packageName == ownPackage || ongoing || groupSummary) return false
        if (lastTextByKey[key] == text) return false
        lastTextByKey.remove(key)
        lastTextByKey[key] = text
        val extra = lastTextByKey.size - MAX_KEYS
        if (extra > 0) {
            val oldest = lastTextByKey.keys.take(extra)
            oldest.forEach { lastTextByKey.remove(it) }
        }
        return true
    }
}
