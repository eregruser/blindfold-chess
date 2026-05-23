package com.blindfoldchess.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class MoveSpeechTest {

    @Test fun normalMove() {
        assertEquals("e 2 e 4", MoveSpeech.spoken("e2e4"))
        assertEquals("g 1 f 3", MoveSpeech.spoken("g1f3"))
    }

    @Test fun whiteCastling() {
        assertEquals("castle kingside", MoveSpeech.spoken("e1g1"))
        assertEquals("castle queenside", MoveSpeech.spoken("e1c1"))
    }

    @Test fun blackCastling() {
        assertEquals("castle kingside", MoveSpeech.spoken("e8g8"))
        assertEquals("castle queenside", MoveSpeech.spoken("e8c8"))
    }

    @Test fun promotion() {
        assertEquals("e 7 e 8 queen", MoveSpeech.spoken("e7e8q"))
        assertEquals("a 2 a 1 knight", MoveSpeech.spoken("a2a1n"))
    }

    @Test fun gameOverSentinels() {
        assertEquals("game over", MoveSpeech.spoken(""))
        assertEquals("game over", MoveSpeech.spoken("(none)"))
        assertEquals("game over", MoveSpeech.spoken("0000"))
    }
}
