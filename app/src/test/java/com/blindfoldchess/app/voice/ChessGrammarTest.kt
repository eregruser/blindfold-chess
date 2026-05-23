package com.blindfoldchess.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChessGrammarTest {

    private val grammar = ChessGrammar.fullBoard

    /** Membership check using the wire format ("...","..."). */
    private fun contains(phrase: String): Boolean = grammar.contains("\"$phrase\"")

    @Test fun isWrappedAsJsonArray() {
        assertTrue("must start with [", grammar.startsWith("["))
        assertTrue("must end with ]", grammar.endsWith("]"))
    }

    @Test fun containsAllNatoMoves() {
        assertTrue(contains("echo two echo four"))
        assertTrue(contains("echo seven echo five"))
        assertTrue(contains("bravo one charlie three"))
        assertTrue(contains("golf one foxtrot three"))
        assertTrue(contains("alpha one hotel eight"))
    }

    @Test fun doesNotIncludeLetterFiles() {
        // Per Phase 3d field findings — letter form is parser-only, not in voice grammar.
        assertFalse(contains("e two e four"))
        assertFalse(contains("e2 e4"))
    }

    @Test fun doesNotIncludeToConnector() {
        // "to" / "two" are homophones; the connector is intentionally dropped to avoid
        // coin-flip recognition.
        assertFalse(contains("echo two to echo four"))
        assertFalse(contains("alpha one to hotel eight"))
    }

    @Test fun containsPromotions() {
        // White: rank 7 -> rank 8. Black: rank 2 -> rank 1. No "to" connector.
        assertTrue(contains("echo seven echo eight promote queen"))
        assertTrue(contains("echo seven echo eight promote knight"))
        assertTrue(contains("alpha two alpha one promote queen"))
        assertTrue(contains("foxtrot seven golf eight promote rook"))
    }

    @Test fun doesNotIncludePromotionFromMiddleRanks() {
        assertFalse(contains("echo four echo five promote queen"))
        assertFalse(contains("alpha three alpha four promote queen"))
    }

    @Test fun containsCastling() {
        assertTrue(contains("castle king side"))
        assertTrue(contains("castle queen side"))
        assertTrue(contains("short castle"))
        assertTrue(contains("long castle"))
    }

    @Test fun doesNotIncludeCompoundCastle() {
        // "kingside" / "queenside" appear OOV in the small Vosk model.
        assertFalse(contains("castle kingside"))
        assertFalse(contains("castle queenside"))
    }

    @Test fun excludesNoOpMoves() {
        assertFalse(contains("echo four echo four"))
    }

    @Test fun hasUnknownFallback() {
        assertTrue(contains("[unk]"))
    }

    @Test fun phraseCountInExpectedRange() {
        // 4032 base moves + ~256 promotion variants + 4 castles + 1 [unk] ≈ 4.3k.
        val sepCount = countOccurrences(grammar, "\", \"")
        val phraseCount = sepCount + 1
        assertTrue("got $phraseCount phrases", phraseCount in 4_000..5_000)
    }

    @Test fun noEmptyPhrases() {
        assertEquals(0, countOccurrences(grammar, "\"\""))
    }

    private fun countOccurrences(s: String, sub: String): Int {
        if (sub.isEmpty()) return 0
        var count = 0
        var i = 0
        while (true) {
            val found = s.indexOf(sub, i)
            if (found < 0) break
            count++
            i = found + sub.length
        }
        return count
    }
}
