package io.github.eregruser.blindfoldchess.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoveParserTest {

    private val parser = MoveParser()

    private fun normal(from: String, to: String, promotion: Char? = null) =
        MoveParser.Parsed.Normal(from, to, promotion)

    // ---- long algebraic, letters + digits ----

    @Test fun digitsWithTo() {
        assertEquals(normal("e2", "e4"), parser.parse("e 2 to e 4"))
    }

    @Test fun digitsWithoutTo() {
        assertEquals(normal("e2", "e4"), parser.parse("e 2 e 4"))
    }

    @Test fun squareAsOneToken() {
        assertEquals(normal("e2", "e4"), parser.parse("e2 to e4"))
        assertEquals(normal("g1", "f3"), parser.parse("g1 f3"))
    }

    @Test fun spelledNumbers() {
        assertEquals(normal("e2", "e4"), parser.parse("e two to e four"))
        assertEquals(normal("h7", "h8"), parser.parse("h seven to h eight"))
    }

    // ---- NATO ----

    @Test fun natoFiles() {
        assertEquals(normal("e2", "e4"), parser.parse("echo two to echo four"))
        assertEquals(normal("a1", "h8"), parser.parse("alpha one hotel eight"))
        assertEquals(normal("b1", "c3"), parser.parse("bravo one to charlie three"))
    }

    @Test fun natoMixedWithDigits() {
        assertEquals(normal("e2", "e4"), parser.parse("echo 2 echo 4"))
    }

    // ---- castling ----

    @Test fun castleKingsideVariants() {
        assertEquals(MoveParser.Parsed.CastleKingside, parser.parse("castle kingside"))
        assertEquals(MoveParser.Parsed.CastleKingside, parser.parse("castle short"))
        assertEquals(MoveParser.Parsed.CastleKingside, parser.parse("castles king"))
    }

    @Test fun castleQueensideVariants() {
        assertEquals(MoveParser.Parsed.CastleQueenside, parser.parse("castle queenside"))
        assertEquals(MoveParser.Parsed.CastleQueenside, parser.parse("castle long"))
        assertEquals(MoveParser.Parsed.CastleQueenside, parser.parse("castles queen"))
    }

    @Test fun ambiguousCastleReturnsNull() {
        assertNull(parser.parse("castle"))
        assertNull(parser.parse("castle kingside queenside"))
    }

    // ---- promotion ----

    @Test fun promotionWithTo() {
        assertEquals(normal("e7", "e8", 'q'), parser.parse("e7 to e8 promote to queen"))
        assertEquals(normal("a7", "a8", 'n'), parser.parse("alpha seven to alpha eight promote to knight"))
    }

    @Test fun promotionWithoutTo() {
        assertEquals(normal("e7", "e8", 'r'), parser.parse("e7 to e8 promote rook"))
    }

    @Test fun horseIsKnight() {
        assertEquals(normal("e7", "e8", 'n'), parser.parse("e7 to e8 promote to horse"))
    }

    // ---- toUci ----

    @Test fun normalToUci() {
        assertEquals("e2e4", normal("e2", "e4").toUci())
        assertEquals("e7e8q", normal("e7", "e8", 'q').toUci())
    }

    // ---- failure cases ----

    @Test fun emptyInput() {
        assertNull(parser.parse(""))
        assertNull(parser.parse("   "))
    }

    @Test fun garbageInput() {
        assertNull(parser.parse("hello world"))
        assertNull(parser.parse("the quick brown fox"))
    }

    @Test fun singleSquareIsNotAMove() {
        assertNull(parser.parse("e2"))
        assertNull(parser.parse("echo two"))
    }

    @Test fun ignoresExtraneousFillerWords() {
        assertEquals(normal("e2", "e4"), parser.parse("please move e2 to e4"))
        assertEquals(normal("e2", "e4"), parser.parse("um e two to e four"))
    }
}
