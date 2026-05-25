package io.github.eregruser.blindfoldchess.chess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FenTest {

    private val startpos = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

    @Test fun parsesStartingPosition() {
        val b = Fen.parse(startpos)
        assertEquals(Color.White, b.sideToMove)
        assertEquals("KQkq", b.castlingRights)
        assertNull(b.enPassantSquare)
        assertEquals(0, b.halfmoveClock)
        assertEquals(1, b.fullmoveNumber)

        // White back rank
        assertEquals(Piece(PieceType.Rook, Color.White), b.pieceAt("a1"))
        assertEquals(Piece(PieceType.Knight, Color.White), b.pieceAt("b1"))
        assertEquals(Piece(PieceType.Bishop, Color.White), b.pieceAt("c1"))
        assertEquals(Piece(PieceType.Queen, Color.White), b.pieceAt("d1"))
        assertEquals(Piece(PieceType.King, Color.White), b.pieceAt("e1"))
        assertEquals(Piece(PieceType.Bishop, Color.White), b.pieceAt("f1"))
        assertEquals(Piece(PieceType.Knight, Color.White), b.pieceAt("g1"))
        assertEquals(Piece(PieceType.Rook, Color.White), b.pieceAt("h1"))

        // White pawns
        for (file in 'a'..'h') {
            assertEquals(
                "white pawn on ${file}2",
                Piece(PieceType.Pawn, Color.White),
                b.pieceAt("${file}2"),
            )
        }
        // Black pawns
        for (file in 'a'..'h') {
            assertEquals(
                "black pawn on ${file}7",
                Piece(PieceType.Pawn, Color.Black),
                b.pieceAt("${file}7"),
            )
        }
        // Black back rank
        assertEquals(Piece(PieceType.King, Color.Black), b.pieceAt("e8"))
        assertEquals(Piece(PieceType.Queen, Color.Black), b.pieceAt("d8"))

        // Center is empty
        assertNull(b.pieceAt("d4"))
        assertNull(b.pieceAt("e4"))
        assertNull(b.pieceAt("f5"))
    }

    @Test fun parsesAfterE4WithEnPassant() {
        val b = Fen.parse("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1")
        assertEquals(Color.Black, b.sideToMove)
        assertEquals("e3", b.enPassantSquare)
        assertEquals(Piece(PieceType.Pawn, Color.White), b.pieceAt("e4"))
        assertNull(b.pieceAt("e2"))
    }

    @Test fun parsesNoCastlingRights() {
        val b = Fen.parse("4k3/8/8/8/8/8/8/4K3 w - - 10 50")
        assertEquals("-", b.castlingRights)
        assertNull(b.enPassantSquare)
        assertEquals(10, b.halfmoveClock)
        assertEquals(50, b.fullmoveNumber)
        // Only two kings
        assertEquals(2, b.squares.count { it != null })
    }

    @Test fun parsesPartialCastlingRights() {
        val b = Fen.parse("r3k2r/8/8/8/8/8/8/R3K2R w Kq - 0 1")
        assertEquals("Kq", b.castlingRights)
    }

    @Test fun handlesMissingHalfmoveFullmove() {
        // Some engines emit only the 4 required fields
        val b = Fen.parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -")
        assertEquals(0, b.halfmoveClock)
        assertEquals(1, b.fullmoveNumber)
    }

    @Test fun piecesOfReturnsCorrectColor() {
        val b = Fen.parse(startpos)
        val whitePieces = b.piecesOf(Color.White)
        assertEquals(16, whitePieces.size)
        assertTrue(whitePieces.any { it.first == "e1" && it.second.type == PieceType.King })
        assertFalse(whitePieces.any { it.second.color == Color.Black })

        val blackPieces = b.piecesOf(Color.Black)
        assertEquals(16, blackPieces.size)
    }

    @Test fun squareIndexRoundtrips() {
        for (file in 'a'..'h') {
            for (rank in '1'..'8') {
                val name = "$file$rank"
                val idx = Board.squareIndex(name)
                assertEquals(name, Board.squareName(idx))
            }
        }
    }

    @Test fun a1IsIndexZero() {
        assertEquals(0, Board.squareIndex("a1"))
        assertEquals("a1", Board.squareName(0))
    }

    @Test fun h8IsIndex63() {
        assertEquals(63, Board.squareIndex("h8"))
        assertEquals("h8", Board.squareName(63))
    }
}
