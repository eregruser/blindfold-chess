package io.github.eregruser.blindfoldchess.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandParserTest {

    private val parser = VoiceCommandParser()

    @Test fun fallsThroughToMoveParser() {
        val cmd = parser.parse("echo two echo four")
        assertTrue(cmd is VoiceCommand.Move)
        val move = (cmd as VoiceCommand.Move).parsed
        assertEquals(MoveParser.Parsed.Normal("e2", "e4"), move)
    }

    @Test fun fallsThroughToCastleMove() {
        val cmd = parser.parse("castle king side")
        assertTrue(cmd is VoiceCommand.Move)
        assertEquals(MoveParser.Parsed.CastleKingside, (cmd as VoiceCommand.Move).parsed)
    }

    @Test fun repeat() {
        assertEquals(VoiceCommand.Repeat, parser.parse("repeat"))
        assertEquals(VoiceCommand.Repeat, parser.parse("repeat that"))
        assertEquals(VoiceCommand.Repeat, parser.parse("repeat the move"))
    }

    @Test fun takeBack() {
        assertEquals(VoiceCommand.TakeBack, parser.parse("take back"))
        assertEquals(VoiceCommand.TakeBack, parser.parse("undo"))
        assertEquals(VoiceCommand.TakeBack, parser.parse("undo that"))
    }

    @Test fun whoseTurn() {
        assertEquals(VoiceCommand.WhoseTurn, parser.parse("whose turn"))
        assertEquals(VoiceCommand.WhoseTurn, parser.parse("whose turn is it"))
    }

    @Test fun howManyMoves() {
        assertEquals(VoiceCommand.HowManyMoves, parser.parse("how many moves"))
    }

    @Test fun listPieces() {
        assertEquals(VoiceCommand.ListPieces, parser.parse("list my pieces"))
        assertEquals(VoiceCommand.ListPieces, parser.parse("list pieces"))
    }

    @Test fun describeBoard() {
        assertEquals(VoiceCommand.DescribeBoard, parser.parse("describe board"))
        assertEquals(VoiceCommand.DescribeBoard, parser.parse("describe the board"))
    }

    @Test fun resign() {
        assertEquals(VoiceCommand.Resign, parser.parse("resign"))
    }

    @Test fun newGame() {
        assertEquals(VoiceCommand.NewGame, parser.parse("new game"))
    }

    @Test fun readMoves() {
        assertEquals(VoiceCommand.ReadMoves, parser.parse("read moves"))
        assertEquals(VoiceCommand.ReadMoves, parser.parse("read the moves"))
    }

    @Test fun describeSquareNato() {
        assertEquals(VoiceCommand.DescribeSquare("e3"), parser.parse("describe square echo three"))
        assertEquals(VoiceCommand.DescribeSquare("h8"), parser.parse("describe square hotel eight"))
        assertEquals(VoiceCommand.DescribeSquare("d4"), parser.parse("describe square delta four"))
    }

    @Test fun describeSquareWithLetterFile() {
        assertEquals(VoiceCommand.DescribeSquare("f3"), parser.parse("describe square f three"))
    }

    @Test fun describeSquareRejectsBadSquare() {
        assertNull(parser.parse("describe square alpha nine"))
        assertNull(parser.parse("describe square bogus square"))
    }

    @Test fun rejectsLegacyWhatsOnPhrasing() {
        // The earlier "what is on" / "whats on" phrasings were retired because the
        // "on" token collided acoustically with "one" in the small Vosk model.
        assertNull(parser.parse("whats on alpha one"))
        assertNull(parser.parse("what is on hotel eight"))
    }

    @Test fun returnsNullForUnparseable() {
        assertNull(parser.parse(""))
        assertNull(parser.parse("hello world"))
    }
}
