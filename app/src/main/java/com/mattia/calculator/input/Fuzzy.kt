package com.mattia.calculator.input

/**
 * fzf-style fuzzy matching, as used by Ctrl+R in fish/nushell/fzf.
 *
 * The query is split on spaces and every term must match (AND), each as a
 * case-insensitive subsequence. For each term we take the first subsequence match,
 * then shrink it backwards to the shortest window (fzf "v1" algorithm), and score it:
 *   +16 per matched char, +8 consecutive bonus, +10 word-boundary bonus,
 *   +12 if the match starts the text, -3 gap opening, -1 per further gap char.
 */
object Fuzzy {

    data class Match(val score: Int, val positions: List<Int>)

    fun match(query: String, text: String): Match? {
        val terms = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return Match(0, emptyList())
        var total = 0
        val positions = sortedSetOf<Int>()
        for (term in terms) {
            val m = matchTerm(term, text) ?: return null
            total += m.score
            positions += m.positions
        }
        return Match(total, positions.toList())
    }

    private fun matchTerm(term: String, text: String): Match? {
        val q = term.lowercase()
        val t = text.lowercase()
        if (q.length > t.length) return null
        // forward pass: find the end of the first subsequence match
        var qi = 0
        var end = -1
        for (i in t.indices) {
            if (t[i] == q[qi]) {
                qi++
                if (qi == q.length) { end = i; break }
            }
        }
        if (end < 0) return null
        // backward pass: shrink to the shortest window ending at `end`
        qi = q.length - 1
        var start = end
        for (i in end downTo 0) {
            if (t[i] == q[qi]) {
                qi--
                if (qi < 0) { start = i; break }
            }
        }
        // score the window, collecting positions left to right
        val pos = ArrayList<Int>(q.length)
        var score = 0
        qi = 0
        var prev = -2
        var inGap = false
        for (i in start..end) {
            if (qi < q.length && t[i] == q[qi]) {
                score += 16
                if (prev == i - 1) score += 8
                if (i == 0 || !text[i - 1].isLetterOrDigit()) score += 10
                pos += i
                prev = i
                qi++
                inGap = false
            } else {
                score -= if (inGap) 1 else 3
                inGap = true
            }
        }
        if (start == 0) score += 12
        return Match(score, pos)
    }

    /** Rank [items] for [query]: best score first, ties broken by original order (recency). */
    fun <T> rank(query: String, items: List<T>, key: (T) -> String): List<Pair<T, Match>> =
        items.mapIndexedNotNull { idx, item -> match(query, key(item))?.let { Triple(idx, item, it) } }
            .sortedWith(compareByDescending<Triple<Int, T, Match>> { it.third.score }.thenBy { it.first })
            .map { it.second to it.third }
}
