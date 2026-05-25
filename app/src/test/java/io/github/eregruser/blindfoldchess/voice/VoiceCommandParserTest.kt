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

    @Test fun whatsOnWithApostrophe() {
        assertEquals(VoiceCommand.WhatsOn("e3"), parser.parse("what's on echo three"))
    }

    @Test fun whatsOnNoApostrophe() {
        assertEquals(VoiceCommand.WhatsOn("a1"), parser.parse("whats on alpha one"))
    }

    @Test fun whatIsOn() {
        assertEquals(VoiceCommand.WhatsOn("h8"), parser.parse("what is on hotel eight"))
        assertEquals(VoiceCommand.WhatsOn("d4"), parser.parse("what is on delta four"))
    }

    @Test fun whatsOnWithLetterFile() {
        assertEquals(VoiceCommand.WhatsOn("f3"), parser.parse("whats on f three"))
    }

    @Test fun whatsOnRejectsBadSquare() {
        assertNull(parser.parse("whats on alpha nine"))
        assertNull(parser.parse("what is on bogus square"))
    }

    @Test fun returnsNullForUnparseable() {
        assertNull(parser.parse(""))
        assertNull(parser.parse("hello world"))
    }
}
