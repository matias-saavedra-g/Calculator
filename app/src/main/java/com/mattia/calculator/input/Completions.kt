package com.mattia.calculator.input

/**
 * Autocomplete for the accessory bar + fish-style ghost suggestions.
 * Pure Kotlin (no Android types) so it is unit-tested on the JVM.
 */
data class Completion(
    /** Text shown on the chip. */
    val label: String,
    /** Text that replaces the word being typed. */
    val insert: String,
    /** How many chars from the end of [insert] the caret should land (e.g. 1 for "sqrt()"). */
    val caretBack: Int = 0,
    val kind: Kind = Kind.Function,
    val hint: String = "",
) {
    enum class Kind { Command, Function, Constant, Keyword, Wolfram }
}

/** Gray text shown after the caret; Tab accepts it. */
data class Ghost(
    val text: String,
    val caretBack: Int = 0,
    val fromHistory: Boolean,
    /** Characters before the caret that the ghost replaces (case fixes, e.g. "Sq" -> "sqrt"). */
    val replaceLen: Int = 0,
) {
    /** What is drawn after the caret. */
    val display: String get() = if (replaceLen > 0) text.substring(replaceLen.coerceAtMost(text.length)) else text
}

/** Result of applying a completion / ghost: new text and caret position. */
data class Edit(val text: String, val caret: Int)

object Completions {

    private fun cmd(w: String, insert: String = "$w ", hint: String = "") = Completion(w, insert, 0, Completion.Kind.Command, hint)
    private fun fn(w: String, hint: String = "") = Completion(w, "$w()", 1, Completion.Kind.Function, hint)
    private fun const(w: String, hint: String = "") = Completion(w, w, 0, Completion.Kind.Constant, hint)
    private fun kw(w: String) = Completion(w, "$w ", 0, Completion.Kind.Keyword)
    private fun wl(w: String) = Completion("$w[]", "$w[]", 1, Completion.Kind.Wolfram)

    val COMMANDS = listOf(
        cmd("solve", hint = "solve x^2 = 4"),
        cmd("int", hint = "int x^2 from 0 to 1"),
        cmd("integrate", hint = "integrate sin x"),
        cmd("sum", hint = "sum 1 to 100"),
        cmd("prod", hint = "prod 1 to 5"),
        cmd("d/dx", hint = "d/dx x^3"),
        cmd("derivative", "derivative of ", "derivative of sin x"),
        cmd("lim", "lim x->0 ", "lim x->0 sin(x)/x"),
        cmd("limit", "limit of ", "limit of 1/x as x->oo"),
        cmd("factor", hint = "factor x^2 - 1"),
        cmd("expand", hint = "expand (x+1)^3"),
        cmd("simplify"),
        cmd("taylor", "taylor series of ", "taylor series of cos x"),
        cmd("series"),
        cmd("roots", "roots of "),
        cmd("partial", "partial fractions "),
        cmd("det"),
        cmd("inverse", "inverse of "),
        cmd("eigenvalues", "eigenvalues of "),
        cmd("numeric"),
        cmd("hex"), cmd("bin"),
    )

    val FUNCTIONS = listOf(
        fn("sqrt"), fn("cbrt"), fn("root"), fn("abs"), fn("sin"), fn("cos"), fn("tan"), fn("cot"),
        fn("sec"), fn("csc"), fn("asin"), fn("acos"), fn("atan"), fn("sinh"), fn("cosh"), fn("tanh"),
        fn("ln"), fn("log"), fn("log2"), fn("log10"), fn("exp"), fn("floor"), fn("ceil"),
        fn("gcd"), fn("lcm"), fn("binomial"), fn("factorial"), fn("gamma"), fn("erf"), fn("zeta"),
        fn("max"), fn("min"), fn("mod"), fn("re"), fn("im"), fn("arg"), fn("conjugate"), fn("isprime"),
        fn("prime"), fn("fibonacci"), fn("totient"),
    )

    val CONSTANTS = listOf(
        const("pi", "π"), const("e", "Euler's number"), const("oo", "∞"), const("infinity"),
        const("ans", "last answer"), const("phi", "golden ratio"), const("tau", "2π"), const("deg", "degree"),
    )

    val KEYWORDS = listOf(kw("from"), kw("to"), kw("for"), kw("wrt"), kw("where"), kw("as"), kw("of"), kw("step"), kw("order"), kw("around"))

    val WOLFRAM = listOf(
        "Integrate", "Solve", "Sum", "Product", "D", "Limit", "Series", "N", "Factor", "Expand",
        "Simplify", "FullSimplify", "Apart", "FactorInteger", "PrimeQ", "Det", "Inverse", "Eigenvalues",
        "Sqrt", "Sin", "Cos", "Tan", "Log", "Exp", "Abs",
    ).map { wl(it) }

    val ALL = COMMANDS + FUNCTIONS + CONSTANTS + KEYWORDS + WOLFRAM

    /** Chips shown when the input is empty. */
    val STARTERS = listOf("solve", "int", "sum", "d/dx", "lim", "factor").map { w -> COMMANDS.first { it.label == w } } +
        listOf(FUNCTIONS.first { it.label == "sqrt" }, CONSTANTS.first { it.label == "pi" }, CONSTANTS.first { it.label == "ans" })

    private val WORD = Regex("[A-Za-z_][A-Za-z0-9_]*$")

    /** Start index and text of the identifier that ends at [caret] ("" when none). */
    fun wordAt(text: String, caret: Int): Pair<Int, String> {
        val before = text.substring(0, caret.coerceIn(0, text.length))
        val m = WORD.find(before) ?: return caret to ""
        // "2sin" -> the word is "sin"; "x2" stays "x2"
        return m.range.first to m.value
    }

    /** Context-aware keyword hints for when the caret follows a space. */
    private fun contextual(text: String): List<Completion> {
        val t = text.trimEnd().lowercase()
        if (t.isEmpty()) return emptyList()
        val out = mutableListOf<Completion>()
        val first = t.substringBefore(' ')
        val words = t.split(Regex("\\s+"))
        fun kw(w: String) = KEYWORDS.first { it.label == w }
        when (first) {
            "int", "integrate", "integral" -> {
                if ("from" !in words) { out += Completion("dx", "dx ", 0, Completion.Kind.Keyword); out += kw("from") }
                else if ("to" !in words) out += kw("to")
            }
            "sum", "prod", "product" -> {
                if ("from" !in words && "to" !in words) { out += kw("to"); out += kw("from") }
                else if ("to" !in words) out += kw("to")
                else if ("step" !in words) out += kw("step")
            }
            "solve" -> if ("for" !in words && '=' in t) out += kw("for")
            "lim", "limit" -> if ("as" !in words && first == "limit") out += kw("as")
            "derivative" -> if ("wrt" !in words) out += kw("wrt")
            "taylor", "series" -> if ("around" !in words) out += kw("around")
        }
        return out
    }

    /**
     * Completions for the word at the caret. Prefix matches first (commands first when the
     * word starts the line), then fuzzy subsequence matches ("sqr" -> sqrt, "itg" -> integrate).
     */
    fun suggest(text: String, caret: Int, limit: Int = 12): List<Completion> {
        if (text.isBlank()) return STARTERS
        val (start, word) = wordAt(text, caret)
        if (word.isEmpty()) {
            val prev = text.getOrNull(caret - 1)
            return if (prev == ' ') contextual(text.substring(0, caret)) else emptyList()
        }
        val atLineStart = text.substring(0, start).isBlank()
        val prefix = ALL.filter { it.label.startsWith(word, ignoreCase = true) && !(it.label == word && it.insert.trim() == word) }
            .sortedWith(
                compareBy<Completion>(
                    { !it.label.startsWith(word) },                                   // exact-case first
                    { if (atLineStart) it.kind != Completion.Kind.Command else it.kind == Completion.Kind.Command },
                    { it.kind == Completion.Kind.Wolfram && !word[0].isUpperCase() },
                    { it.label.length },
                ),
            )
        val fuzzy = if (word.length < 2) emptyList() else
            Fuzzy.rank(word, ALL.filter { it !in prefix && it.label.first().equals(word.first(), true) }) { it.label }
                .filter { it.second.score >= word.length * 16 }
                .map { it.first }
        return (prefix + fuzzy).distinctBy { it.label }.take(limit)
    }

    /**
     * The gray suggestion after the caret:
     *  1. fish-style: the most recent history line that starts with the whole input;
     *  2. otherwise the best completion of the current word.
     * Only offered when the caret is at the end of the text.
     */
    fun ghost(text: String, caret: Int, historyNewestFirst: List<String>, suggestions: List<Completion>): Ghost? {
        if (text.isEmpty() || caret != text.length) return null
        historyNewestFirst.firstOrNull { it.length > text.length && it.startsWith(text) }?.let {
            return Ghost(it.substring(text.length), fromHistory = true)
        }
        val (_, word) = wordAt(text, caret)
        if (word.isEmpty()) return null
        val top = suggestions.firstOrNull { it.insert.startsWith(word, ignoreCase = true) && it.insert.length > word.length }
            ?: return null
        // Replace the typed word when its case differs ("Sq" -> "sqrt()")
        return if (top.insert.startsWith(word)) {
            Ghost(top.insert.substring(word.length), top.caretBack, fromHistory = false)
        } else {
            Ghost(top.insert, top.caretBack, fromHistory = false, replaceLen = word.length)
        }
    }

    fun applyGhost(text: String, ghost: Ghost): Edit {
        val base = text.substring(0, text.length - ghost.replaceLen)
        val newText = base + ghost.text
        return Edit(newText, newText.length - ghost.caretBack)
    }

    fun apply(text: String, caret: Int, c: Completion): Edit {
        val (start, word) = wordAt(text, caret)
        val from = if (word.isEmpty()) caret else start
        var insert = c.insert
        // keep one space between a previous token and a keyword/command
        if (c.kind == Completion.Kind.Keyword && from > 0 && text[from - 1] != ' ') insert = " $insert"
        val after = text.substring(caret)
        val newText = text.substring(0, from) + insert + after
        return Edit(newText, from + insert.length - c.caretBack)
    }
}
