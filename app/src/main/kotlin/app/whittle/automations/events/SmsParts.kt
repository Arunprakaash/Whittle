package app.whittle.automations.events

/**
 * A long text arrives as several parts. Joins the parts of each sender, in arrival order, into one text, so a
 * long message is one event; senders keep the order of their first part.
 */
fun joinParts(parts: List<Pair<String, String>>): List<Pair<String, String>> {
    val joined = LinkedHashMap<String, StringBuilder>()
    for ((sender, text) in parts) joined.getOrPut(sender) { StringBuilder() }.append(text)
    return joined.map { (sender, text) -> sender to text.toString() }
}
