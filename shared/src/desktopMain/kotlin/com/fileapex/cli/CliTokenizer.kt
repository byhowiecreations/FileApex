package com.fileapex.cli

object CliTokenizer {
    /**
     * Splits a raw command string into individual arguments following shell quoting rules:
     * - Double quotes ("...") preserve spaces and escapes.
     * - Single quotes ('...') preserve literal contents.
     * - Unquoted whitespace acts as delimiters.
     * - Backslash escapes (\) are supported.
     */
    fun tokenize(input: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inDoubleQuotes = false
        var inSingleQuotes = false
        var isEscaped = false

        for (char in input) {
            when {
                isEscaped -> {
                    current.append(char)
                    isEscaped = false
                }
                char == '\\' && !inSingleQuotes -> {
                    isEscaped = true
                }
                char == '"' && !inSingleQuotes -> {
                    inDoubleQuotes = !inDoubleQuotes
                }
                char == '\'' && !inDoubleQuotes -> {
                    inSingleQuotes = !inSingleQuotes
                }
                char.isWhitespace() && !inDoubleQuotes && !inSingleQuotes -> {
                    if (current.isNotEmpty()) {
                        tokens.add(current.toString())
                        current.setLength(0)
                    }
                }
                else -> {
                    current.append(char)
                }
            }
        }

        if (current.isNotEmpty()) {
            tokens.add(current.toString())
        }

        return tokens
    }

    /**
     * Cleans an array of arguments, stripping outer quotes if they were preserved by the shell
     * or wrapper scripts, and re-tokenizing single-string argument inputs if necessary.
     */
    fun normalizeArgs(args: Array<String>): List<String> {
        if (args.isEmpty()) return emptyList()
        if (args.size == 1 && (args[0].contains(' ') || args[0].contains('"') || args[0].contains('\''))) {
            val tokenized = tokenize(args[0])
            if (tokenized.isNotEmpty()) return tokenized
        }
        return args.map { stripQuotes(it) }
    }

    fun stripQuotes(s: String): String {
        val trimmed = s.trim()
        if (trimmed.length >= 2) {
            if ((trimmed.startsWith('"') && trimmed.endsWith('"')) ||
                (trimmed.startsWith('\'') && trimmed.endsWith('\''))
            ) {
                return trimmed.substring(1, trimmed.length - 1)
            }
        }
        return trimmed
    }
}
