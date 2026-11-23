package com.mattia.calculator.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyTest {
    @Test fun subsequenceMatches() {
        val m = Fuzzy.match("intx2", "int x^2 from 0 to 1")
        assertNotNull(m)
        assertEquals(listOf(0, 1, 2, 4, 6), m!!.positions)
    }

    @Test fun noMatch() = assertNull(Fuzzy.match("zzz", "int x^2"))

    @Test fun multipleTermsAreAnded() {
        assertNotNull(Fuzzy.match("sum 100", "sum 1 to 100"))
        assertNull(Fuzzy.match("sum 999", "sum 1 to 100"))
    }

    @Test fun consecutiveAndPrefixRankHigher() {
        val ranked = Fuzzy.rank("int", listOf("sin(t)", "x int", "int x^2")) { it }.map { it.first }
        assertEquals("int x^2", ranked.first())
        assertEquals("sin(t)", ranked.last())
    }

    @Test fun emptyQueryKeepsOrder() {
        val ranked = Fuzzy.rank("", listOf("b", "a")) { it }.map { it.first }
        assertEquals(listOf("b", "a"), ranked)
    }
}

class CompletionsTest {
    @Test fun startersWhenEmpty() = assertEquals(Completions.STARTERS, Completions.suggest("", 0))

    @Test fun prefixCompletesCommandAtLineStart() {
        val s = Completions.suggest("in", 2)
        assertEquals("int", s.first().label)
    }

    @Test fun functionCompletionPutsCaretInsideParens() {
        val c = Completions.FUNCTIONS.first { it.label == "sqrt" }
        val e = Completions.apply("2 + sq", 6, c)
        assertEquals("2 + sqrt()", e.text)
        assertEquals(9, e.caret)
    }

    @Test fun wordAfterDigitIsCompleted() {
        assertEquals(1 to "si", Completions.wordAt("2si", 3))
    }

    @Test fun contextualKeywords() {
        val labels = Completions.suggest("int x^2 ", 8).map { it.label }
        assertTrue("from" in labels)
        val sum = Completions.suggest("sum 1 ", 6).map { it.label }
        assertTrue("to" in sum)
    }

    @Test fun ghostPrefersHistory() {
        val g = Completions.ghost("int", 3, listOf("int x^2 from 0 to 1"), Completions.suggest("int", 3))
        assertEquals(" x^2 from 0 to 1", g!!.text)
        assertTrue(g.fromHistory)
    }

    @Test fun ghostFallsBackToCompletion() {
        val g = Completions.ghost("sq", 2, emptyList(), Completions.suggest("sq", 2))
        assertEquals("rt()", g!!.text)
        val e = Completions.applyGhost("sq", g)
        assertEquals("sqrt()", e.text)
        assertEquals(5, e.caret)
    }

    @Test fun capitalisedWordPrefersWolfram() {
        val g = Completions.ghost("Sq", 2, emptyList(), Completions.suggest("Sq", 2))!!
        assertEquals("rt[]", g.display)
        assertEquals("Sqrt[]", Completions.applyGhost("Sq", g).text)
    }

    @Test fun ghostFixesCase() {
        val g = Completions.ghost("SO", 2, emptyList(), Completions.suggest("SO", 2))!!
        assertEquals("lve ", g.display)
        assertEquals("solve ", Completions.applyGhost("SO", g).text)
    }

    @Test fun noGhostWhenCaretNotAtEnd() = assertNull(Completions.ghost("int x", 2, listOf("int x^2"), emptyList()))

    @Test fun keywordGetsLeadingSpace() {
        val from = Completions.KEYWORDS.first { it.label == "from" }
        assertEquals("int x^2 from ", Completions.apply("int x^2", 7, from).text)
    }
}
