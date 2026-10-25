package app.whittle.automations

import java.util.regex.Pattern

/**
 * Hides one-time codes in a text before it is stored or shown to a model. A number that looks like a code
 * ([CodeMaskConfig.codePattern]) is replaced when a keyword such as "OTP" is within [CodeMaskConfig.nearChars]
 * of it. Amounts and dates are never hidden. When unsure it hides: a lone 6-digit number in a short message goes
 * even with no keyword.
 */
class CodeMask(private val config: CodeMaskConfig) {
    private val code = Pattern.compile(config.codePattern, Pattern.CASE_INSENSITIVE)
    private val amount = Pattern.compile(config.amountPattern)
    private val date = Pattern.compile(config.datePattern)
    private val currencyBefore = Pattern.compile(config.currencyBeforePattern, Pattern.CASE_INSENSITIVE)
    // A keyword starts a word but may carry a suffix ("codes", "passcodes"), so only its start is anchored.
    private val keyword = Pattern.compile(
        config.keywords.joinToString("|", prefix = "(?<![\\p{L}\\p{N}])(?:", postfix = ")") { Pattern.quote(it) },
        Pattern.CASE_INSENSITIVE,
    )

    fun mask(text: String): String {
        val found = code.matcher(text)
        val candidates = buildList {
            while (found.find()) if (!isAmountOrDate(text, found.start(), found.end())) add(found.start() to found.end())
        }
        if (candidates.isEmpty()) return text
        val loneSixDigits = candidates.size == 1 && wordCount(text) <= config.shortMessageWords && isSixDigits(text, candidates.single())
        val hidden = candidates.filter { (start, end) -> loneSixDigits || keywordNear(text, start, end) }
        if (hidden.isEmpty()) return text
        val out = StringBuilder()
        var from = 0
        for ((start, end) in hidden) {
            out.append(text, from, start).append(config.replacement)
            from = end
        }
        return out.append(text, from, text.length).toString()
    }

    private fun keywordNear(text: String, start: Int, end: Int): Boolean {
        val before = keyword.matcher(text).region(maxOf(0, start - config.nearChars), start)
        val after = keyword.matcher(text).region(end, minOf(text.length, end + config.nearChars))
        // Transparent bounds so a keyword's first letter is judged by what really precedes it, not the window edge.
        return listOf(before, after).any { it.useTransparentBounds(true).useAnchoringBounds(false).find() }
    }

    private fun isAmountOrDate(text: String, start: Int, end: Int): Boolean =
        overlaps(amount, text, start, end) || overlaps(date, text, start, end) || currencyBefore.matcher(text).region(maxOf(0, start - CURRENCY_LOOKBACK), start).find()

    private fun overlaps(pattern: Pattern, text: String, start: Int, end: Int): Boolean {
        val m = pattern.matcher(text)
        while (m.find()) if (m.start() < end && start < m.end()) return true
        return false
    }

    private fun isSixDigits(text: String, span: Pair<Int, Int>): Boolean =
        text.substring(span.first, span.second).count { it.isDigit() } == config.loneCodeDigits

    private fun wordCount(text: String): Int = text.trim().split(WHITESPACE).count { it.isNotEmpty() }

    private companion object {
        const val CURRENCY_LOOKBACK = 8
        val WHITESPACE = Regex("\\s+")
    }
}
