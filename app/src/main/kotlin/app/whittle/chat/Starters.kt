package app.whittle.chat

import app.whittle.settings.Starter

/** The starters to offer: all of [all] but those needing a phone feature in [off]. */
fun startersFor(all: List<Starter>, off: Set<String>): List<Starter> = all.filter { it.feature == null || it.feature !in off }
