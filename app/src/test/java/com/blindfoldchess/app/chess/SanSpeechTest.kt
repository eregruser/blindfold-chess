package com.blindfoldchess.app.chess

import org.junit.Assert.assertEquals
import org.junit.Test

class SanSpeechTest {

    @Test fun simplePawnMove() {
        assertEquals("e four", SanSpeech.spoken("e4"))
        assertEquals("h eight", SanSpeech.spoken("h8"))
    }

    @Test fun pieceMove() {
        assertEquals("knight f three", SanSpeech.spoken("Nf3"))
        assertEquals("bishop b five", SanSpeech.spoken("Bb5"))
        assertEquals("queen d one", SanSpeech.spoken("Qd1"))
    }

    @Test fun pawnCapture() {
        assertEquals("e takes d five", SanSpeech.spoken("exd5"))
    }

    @Test fun pieceCapture() {
        assertEquals("knight takes e five", SanSpeech.spoken("Nxe5"))
        assertEquals("queen takes h seven, check", SanSpeech.spoken("Qxh7+"))
    }

    @Test fun fileDisambiguation() {
        assertEquals("knight b d two", SanSpeech.spoken("Nbd2"))
        assertEquals("rook a e one", SanSpeech.spoken("Rae1"))
    }

    @Test fun rankDisambiguation() {
        assertEquals("knight one d two", SanSpeech.spoken("N1d2"))
    }

    @Test fun fullSquareDisambiguation() {
        assertEquals("knight b one d two", SanSpeech.spoken("Nb1d2"))
    }

    @Test fun captureWithDisambiguation() {
        assertEquals("knight b takes d five", SanSpeech.spoken("Nbxd5"))
    }

    @Test fun check() {
        assertEquals("bishop takes e five, check", SanSpeech.spoken("Bxe5+"))
        assertEquals("e four, check", SanSpeech.spoken("e4+"))
    }

    @Test fun mate() {
        assertEquals("knight f three, mate", SanSpeech.spoken("Nf3#"))
        assertEquals("queen takes h seven, mate", SanSpeech.spoken("Qxh7#"))
    }

    @Test fun promotion() {
        assertEquals("e eight promotes to queen", SanSpeech.spoken("e8=Q"))
        assertEquals("e takes d eight promotes to queen, check", SanSpeech.spoken("exd8=Q+"))
        assertEquals("a one promotes to knight", SanSpeech.spoken("a1=N"))
    }

    @Test fun kingsideCastle() {
        assertEquals("castle kingside", SanSpeech.spoken("O-O"))
        assertEquals("castle kingside", SanSpeech.spoken("0-0"))
        assertEquals("castle kingside, check", SanSpeech.spoken("O-O+"))
    }

    @Test fun queensideCastle() {
        assertEquals("castle queenside", SanSpeech.spoken("O-O-O"))
        assertEquals("castle queenside", SanSpeech.spoken("0-0-0"))
        assertEquals("castle queenside, mate", SanSpeech.spoken("O-O-O#"))
    }

    @Test fun stripsAnnotation() {
        // !, ?, !?, ??, !! are commentary annotations — shouldn't reach TTS.
        assertEquals("e four", SanSpeech.spoken("e4!"))
        assertEquals("knight f three", SanSpeech.spoken("Nf3?"))
        assertEquals("knight f three", SanSpeech.spoken("Nf3!?"))
    }

    @Test fun malformedReturnsInput() {
        assertEquals("", SanSpeech.spoken(""))
        // Single char isn't a valid SAN move.
        assertEquals("x", SanSpeech.spoken("x"))
    }
}
