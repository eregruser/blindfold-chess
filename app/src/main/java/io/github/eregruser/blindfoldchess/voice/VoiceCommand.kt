package io.github.eregruser.blindfoldchess.voice

/**
 * Recognized voice input — either a chess move or a query/control command.
 *
 * The parser separates commands from moves so the [GameController] can dispatch each cleanly.
 * Move handling resolves castling to UCI; commands run their own handlers (querying the
 * engine for board state etc).
 */
sealed class VoiceCommand {
    data class Move(val parsed: MoveParser.Parsed) : VoiceCommand()

    /** "repeat" / "repeat that" — re-speak the last engine move. */
    object Repeat : VoiceCommand()

    /** "take back" / "undo" — pop the user's last move plus the engine's reply. */
    object TakeBack : VoiceCommand()

    /** "whose turn" — announce side to move. */
    object WhoseTurn : VoiceCommand()

    /** "how many moves" — announce full-move count. */
    object HowManyMoves : VoiceCommand()

    /** "list my pieces" — announce user-side pieces with their squares. */
    object ListPieces : VoiceCommand()

    /** "describe board" — announce all pieces (white then black). */
    object DescribeBoard : VoiceCommand()

    /** "resign" — end the current game. */
    object Resign : VoiceCommand()

    /** "new game" — reset to startpos. */
    object NewGame : VoiceCommand()

    /** "what is on <square>" — announce piece (or empty) at the named square. */
    data class WhatsOn(val square: String) : VoiceCommand()

    /** "read moves" — TTS every move in chronological order with a pause between each. */
    object ReadMoves : VoiceCommand()
}

class VoiceCommandParser(private val moveParser: MoveParser = MoveParser()) {

    fun parse(input: String): VoiceCommand? {
        val tokens = tokenize(input)
        if (tokens.isEmpty()) return null

        parseExactCommand(tokens)?.let { return it }
        parseWhatsOn(tokens)?.let { return it }

        // Fall through to move parsing
        return moveParser.parse(input)?.let { VoiceCommand.Move(it) }
    }

    private fun tokenize(input: String): List<String> =
        input.lowercase()
            .replace("'", "")
            .split(Regex("[\\s.,\\-]+"))
            .filter { it.isNotBlank() }

    private fun parseExactCommand(tokens: List<String>): VoiceCommand? {
        val joined = tokens.joinToString(" ")
        return EXACT_COMMANDS[joined]
    }

    /**
     * Patterns:
     *   "what is on <file> <rank>"
     *   "whats on <file> <rank>"      (apostrophe stripped in tokenize)
     */
    private fun parseWhatsOn(tokens: List<String>): VoiceCommand.WhatsOn? {
        val squareTokens = when {
            tokens.size == 4 && tokens[0] == "whats" && tokens[1] == "on" ->
                tokens.subList(2, 4)
            tokens.size == 5 && tokens[0] == "what" && tokens[1] == "is" && tokens[2] == "on" ->
                tokens.subList(3, 5)
            else -> return null
        }
        val square = parseSquare(squareTokens) ?: return null
        return VoiceCommand.WhatsOn(square)
    }

    private fun parseSquare(tokens: List<String>): String? {
        if (tokens.size != 2) return null
        val file = parseFile(tokens[0]) ?: return null
        val rank = parseRank(tokens[1]) ?: return null
        return "$file$rank"
    }

    private fun parseFile(token: String): Char? {
        NATO_TO_FILE[token]?.let { return it }
        if (token.length == 1 && token[0] in 'a'..'h') return token[0]
        return null
    }

    private fun parseRank(token: String): Char? {
        SPELLED_TO_DIGIT[token]?.let { return it }
        if (token.length == 1 && token[0] in '1'..'8') return token[0]
        return null
    }

    private companion object {
        val EXACT_COMMANDS: Map<String, VoiceCommand> = mapOf(
            "repeat" to VoiceCommand.Repeat,
            "repeat that" to VoiceCommand.Repeat,
            "repeat the move" to VoiceCommand.Repeat,
            "take back" to VoiceCommand.TakeBack,
            "takeback" to VoiceCommand.TakeBack,
            "undo" to VoiceCommand.TakeBack,
            "undo that" to VoiceCommand.TakeBack,
            "whose turn" to VoiceCommand.WhoseTurn,
            "whose turn is it" to VoiceCommand.WhoseTurn,
            "how many moves" to VoiceCommand.HowManyMoves,
            "list my pieces" to VoiceCommand.ListPieces,
            "list pieces" to VoiceCommand.ListPieces,
            "describe board" to VoiceCommand.DescribeBoard,
            "describe the board" to VoiceCommand.DescribeBoard,
            "resign" to VoiceCommand.Resign,
            "new game" to VoiceCommand.NewGame,
            "read moves" to VoiceCommand.ReadMoves,
            "read the moves" to VoiceCommand.ReadMoves,
        )

        val NATO_TO_FILE = mapOf(
            "alpha" to 'a', "bravo" to 'b', "charlie" to 'c', "delta" to 'd',
            "echo" to 'e', "foxtrot" to 'f', "golf" to 'g', "hotel" to 'h',
        )
        val SPELLED_TO_DIGIT = mapOf(
            "one" to '1', "two" to '2', "three" to '3', "four" to '4',
            "five" to '5', "six" to '6', "seven" to '7', "eight" to '8',
        )
    }
}
