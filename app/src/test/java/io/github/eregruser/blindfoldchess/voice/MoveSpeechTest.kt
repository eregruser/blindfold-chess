package io.github.eregruser.blindfoldchess.voice

import io.github.eregruser.blindfoldchess.data.SettingsRepository.Notation
import org.junit.Assert.assertEquals
import org.junit.Test

class MoveSpeechTest {

    @Test fun letterNormalMove() {
        assertEquals("e 2 e 4", MoveSpeech.spoken("e2e4", Notation.LetterByLetter))
        assertEquals("g 1 f 3", MoveSpeech.spoken("g1f3", Notation.LetterByLetter))
    }

    @Test fun natoNormalMove() {
        assertEquals("echo two echo four", MoveSpeech.spoken("e2e4", Notation.Nato))
        assertEquals("golf one foxtrot three", MoveSpeech.spoken("g1f3", Notation.Nato))
        assertEquals("alpha one hotel eight", MoveSpeech.spoken("a1h8", Notation.Nato))
    }

    @Test fun whiteCastling() {
        assertEquals("castle kingside", MoveSpeech.spoken("e1g1", Notation.LetterByLetter))
        assertEquals("castle queenside", MoveSpeech.spoken("e1c1", Notation.Nato))
    }

    @Test fun blackCastling() {
        assertEquals("castle kingside", MoveSpeech.spoken("e8g8", Notation.LetterByLetter))
        assertEquals("castle queenside", MoveSpeech.spoken("e8c8", Notation.Nato))
    }

    @Test fun letterPromotion() {
        assertEquals("e 7 e 8 queen", MoveSpeech.spoken("e7e8q", Notation.LetterByLetter))
        assertEquals("a 2 a 1 knight", MoveSpeech.spoken("a2a1n", Notation.LetterByLetter))
    }

    @Test fun natoPromotion() {
        assertEquals("echo seven echo eight queen", MoveSpeech.spoken("e7e8q", Notation.Nato))
        assertEquals("alpha two alpha one knight", MoveSpeech.spoken("a2a1n", Notation.Nato))
    }

    @Test fun gameOverSentinels() {
        assertEquals("game over", MoveSpeech.spoken("", Notation.LetterByLetter))
        assertEquals("game over", MoveSpeech.spoken("(none)", Notation.Nato))
        assertEquals("game over", MoveSpeech.spoken("0000", Notation.LetterByLetter))
    }
}
