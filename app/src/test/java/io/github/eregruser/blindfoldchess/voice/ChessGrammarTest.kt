package io.github.eregruser.blindfoldchess.voice

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

    // ---------- ChessGrammar.legal ----------

    @Test fun legalGrammarContainsEachMove() {
        val moves = listOf("e2e4", "d2d4", "g1f3")
        val g = ChessGrammar.legal(moves)
        assertTrue(g.contains("\"echo two echo four\""))
        assertTrue(g.contains("\"delta two delta four\""))
        assertTrue(g.contains("\"golf one foxtrot three\""))
        assertTrue(g.contains("\"[unk]\""))
    }

    @Test fun legalGrammarExpandsCastlingAliases() {
        val g = ChessGrammar.legal(listOf("e1g1"))
        assertTrue(g.contains("\"castle king side\""))
        assertTrue(g.contains("\"short castle\""))
    }

    @Test fun legalGrammarHandlesBlackCastle() {
        val g = ChessGrammar.legal(listOf("e8c8"))
        assertTrue(g.contains("\"castle queen side\""))
        assertTrue(g.contains("\"long castle\""))
    }

    @Test fun legalGrammarHandlesPromotion() {
        val g = ChessGrammar.legal(listOf("e7e8q", "e7e8n"))
        assertTrue(g.contains("\"echo seven echo eight promote queen\""))
        assertTrue(g.contains("\"echo seven echo eight promote knight\""))
    }

    @Test fun legalGrammarEmptyListStillValid() {
        val g = ChessGrammar.legal(emptyList())
        assertTrue(g.startsWith("[\""))
        assertTrue(g.endsWith("\"]"))
        assertTrue(g.contains("\"[unk]\""))
    }

    @Test fun legalGrammarSmallerThanFullBoard() {
        val small = ChessGrammar.legal(listOf("e2e4", "d2d4", "g1f3", "b1c3"))
        // The dynamic legal-move grammar exists specifically to give Vosk a much
        // smaller acoustic search space than the full-board enumeration. Assert
        // that intent directly rather than against an absolute byte threshold —
        // the legal grammar legitimately grew past 1KB once the "what is on
        // <file> <rank>" 64-square matrix was added, but it is still ~200x
        // smaller than fullBoard's full move enumeration.
        assertTrue(
            "legal size=${small.length}, fullBoard size=${ChessGrammar.fullBoard.length}",
            small.length * 10 < ChessGrammar.fullBoard.length,
        )
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
