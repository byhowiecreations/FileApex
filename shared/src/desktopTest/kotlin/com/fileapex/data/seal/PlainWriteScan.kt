package com.fileapex.data.seal

/**
 * Decides whether Kotlin source writes to disk in clear text. Only real code counts: comments and the text of string
 * literals are removed first, so naming a write or the sealing helpers in a comment or a message changes nothing.
 * A file is accepted when it really calls [SealedText] or [SealedStrings]; the check is per file, so a file that mixes
 * sealed and plain writes needs a reason in the guard's list.
 */
object PlainWriteScan {
    private val write = Regex("""\.(writeText|appendText|writeBytes)\(|getSharedPreferences\(|Files\.write""")
    private val sealing = Regex("""\bSealedText\.(seal|open|writeFile|readFile)\(|\bSealedStrings\(""")

    fun writesPlain(source: String): Boolean {
        val code = codeOnly(source)
        return write.containsMatchIn(code) && !sealing.containsMatchIn(code)
    }

    internal fun codeOnly(source: String): String = Scanner(source).run()

    private class Scanner(private val s: String) {
        private var i = 0
        private val out = StringBuilder()

        fun run(): String {
            code(untilBrace = false)
            return out.toString()
        }

        private fun code(untilBrace: Boolean) {
            var depth = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    s.startsWith("//", i) -> while (i < s.length && s[i] != '\n') i++
                    s.startsWith("/*", i) -> blockComment()
                    s.startsWith("\"\"\"", i) -> { out.append("\"\""); i += 3; rawString() }
                    c == '"' -> { out.append("\"\""); i++; string() }
                    c == '\'' -> { out.append("' '"); i++; charLiteral() }
                    c == '{' -> { depth++; out.append(c); i++ }
                    c == '}' -> {
                        if (untilBrace && depth == 0) { i++; return }
                        depth--
                        out.append(c)
                        i++
                    }
                    else -> { out.append(c); i++ }
                }
            }
        }

        private fun blockComment() {
            var depth = 0
            while (i < s.length) {
                when {
                    s.startsWith("/*", i) -> { depth++; i += 2 }
                    s.startsWith("*/", i) -> { depth--; i += 2; if (depth == 0) return }
                    else -> i++
                }
            }
        }

        private fun string() {
            while (i < s.length) {
                when {
                    s[i] == '\\' -> i += 2
                    s[i] == '"' -> { i++; return }
                    s.startsWith("\${", i) -> { i += 2; code(untilBrace = true) }
                    else -> i++
                }
            }
        }

        private fun rawString() {
            while (i < s.length) {
                when {
                    s.startsWith("\"\"\"", i) -> {
                        i += 3
                        while (i < s.length && s[i] == '"') i++
                        return
                    }
                    s.startsWith("\${", i) -> { i += 2; code(untilBrace = true) }
                    else -> i++
                }
            }
        }

        private fun charLiteral() {
            while (i < s.length) {
                when (s[i]) {
                    '\\' -> i += 2
                    '\'' -> { i++; return }
                    else -> i++
                }
            }
        }
    }
}
