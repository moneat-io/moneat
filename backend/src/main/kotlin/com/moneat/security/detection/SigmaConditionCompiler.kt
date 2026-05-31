// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

package com.moneat.security.detection

/**
 * Compiles a Sigma `condition` expression into a single
 * [LogQueryParser][com.moneat.logs.services.LogQueryParser] filter string, resolving each selection
 * identifier to the fragment [SigmaImporter] already mapped for it.
 *
 * Supported grammar (precedence `or` < `and` < `not`, with `(` `)` grouping):
 *
 * ```
 * expr      := orExpr
 * orExpr    := andExpr ( 'or' andExpr )*
 * andExpr   := notExpr ( 'and' notExpr )*
 * notExpr   := 'not' notExpr | atom
 * atom      := '(' expr ')' | quantifier | identifier
 * quantifier:= ( '1' | 'all' ) 'of' ( identifierPattern | 'them' )
 * ```
 *
 * Anything outside this grammar — aggregation pipes (handled earlier), unknown identifiers, `near`,
 * dangling operators — raises a [SigmaImportException]. This compiler builds only a boolean combination
 * of already-safe fragments; it never emits a field/value itself, so it adds no new injection surface
 * (the eventual query still goes through [RuleQueryCompiler]).
 */
internal class SigmaConditionCompiler(
    /** Selection name → its mapped [LogQueryParser][com.moneat.logs.services.LogQueryParser] fragment. */
    private val selections: Map<String, String>,
) {

    private lateinit var tokens: List<String>
    private var pos: Int = 0

    /** Current grouping/`not` nesting depth; guards the recursive descent against stack exhaustion. */
    private var depth: Int = 0

    fun compile(condition: String): String {
        boundRawCondition(condition)
        tokens = tokenize(condition)
        pos = 0
        depth = 0
        if (tokens.isEmpty()) throw SigmaImportException("Sigma condition is empty")
        val node = parseOr()
        if (pos != tokens.size) {
            throw SigmaImportException("Sigma condition has trailing tokens after '${peekSafe()}'")
        }
        return node
    }

    /**
     * Reject an absurdly large or deeply parenthesised raw condition before tokenizing, so a hostile
     * `((((…))))` cannot drive the recursive-descent parser into a [StackOverflowError]. This is a coarse
     * pre-filter; [enterDepth] is the precise per-node guard once we are parsing.
     */
    private fun boundRawCondition(condition: String) {
        if (condition.length > MAX_CONDITION_LENGTH) {
            throw SigmaImportException("Sigma condition is too long (limit $MAX_CONDITION_LENGTH characters)")
        }
        val openParens = condition.count { it == '(' }
        if (openParens > MAX_PARENS) {
            throw SigmaImportException("Sigma condition nests too many parentheses (limit $MAX_PARENS)")
        }
    }

    /** Enters one recursion level, rejecting a condition nested past [MAX_DEPTH] instead of overflowing. */
    private fun enterDepth() {
        if (++depth > MAX_DEPTH) {
            throw SigmaImportException("Sigma condition is nested too deeply (limit $MAX_DEPTH)")
        }
    }

    private fun exitDepth() {
        depth--
    }

    // ── Recursive-descent parser; each level returns a parser-syntax fragment. ──

    private fun parseOr(): String {
        var left = parseAnd()
        while (matchKeyword("or")) {
            val right = parseAnd()
            left = "($left OR $right)"
        }
        return left
    }

    private fun parseAnd(): String {
        var left = parseNot()
        while (matchKeyword("and")) {
            val right = parseNot()
            left = "($left AND $right)"
        }
        return left
    }

    private fun parseNot(): String {
        if (matchKeyword("not")) {
            enterDepth()
            val inner = parseNot()
            exitDepth()
            return "NOT ($inner)"
        }
        return parseAtom()
    }

    private fun parseAtom(): String {
        val token = peek() ?: throw SigmaImportException("Sigma condition ended unexpectedly")
        if (token == "(") {
            advance()
            enterDepth()
            val inner = parseOr()
            exitDepth()
            expect(")")
            return "($inner)"
        }
        if (token == "1" || token.equals("all", ignoreCase = true)) {
            // Could be a quantifier ("1 of …" / "all of …"); only treat as one when 'of' follows.
            if (peek(1)?.equals("of", ignoreCase = true) == true) {
                return parseQuantifier(token)
            }
        }
        return resolveIdentifier(consumeIdentifier())
    }

    private fun parseQuantifier(quantToken: String): String {
        advance() // quantifier ('1' or 'all')
        expectKeyword("of")
        val target = consumeIdentifier()
        val matched = if (target.equals("them", ignoreCase = true)) {
            selections.keys.toList()
        } else {
            resolvePattern(target)
        }
        if (matched.isEmpty()) {
            throw SigmaImportException("Sigma condition '… of $target' matches no selections")
        }
        val fragments = matched.map { resolveIdentifier(it) }
        val op = if (quantToken == "1") "OR" else "AND"
        return "(" + fragments.joinToString(" $op ") + ")"
    }

    /** Resolves a `prefix*` / `*suffix` / `*sub*` selection pattern to the matching selection names. */
    private fun resolvePattern(pattern: String): List<String> {
        if (!pattern.contains("*")) {
            // A bare identifier after "of" (e.g. "1 of selection") refers to that single selection.
            return listOf(pattern)
        }
        val regex = patternToRegex(pattern)
        return selections.keys.filter { regex.matches(it) }
    }

    private fun resolveIdentifier(name: String): String =
        selections[name]
            ?: throw SigmaImportException("Sigma condition references unknown selection '${safe(name)}'")

    // ── Token plumbing ────────────────────────────────────────────────────────

    private fun consumeIdentifier(): String {
        val token = peek() ?: throw SigmaImportException("Sigma condition expected a selection name")
        if (token in STRUCTURAL || token.lowercase() in KEYWORDS) {
            throw SigmaImportException("Sigma condition expected a selection name but found '${safe(token)}'")
        }
        advance()
        return token
    }

    private fun matchKeyword(keyword: String): Boolean {
        if (peek()?.equals(keyword, ignoreCase = true) == true) {
            advance()
            return true
        }
        return false
    }

    private fun expectKeyword(keyword: String) {
        if (!matchKeyword(keyword)) {
            throw SigmaImportException("Sigma condition expected '$keyword' but found '${peekSafe()}'")
        }
    }

    private fun expect(symbol: String) {
        if (peek() != symbol) {
            throw SigmaImportException("Sigma condition expected '$symbol' but found '${peekSafe()}'")
        }
        advance()
    }

    private fun peek(ahead: Int = 0): String? = tokens.getOrNull(pos + ahead)

    private fun peekSafe(): String = safe(peek() ?: "<end>")

    private fun advance() {
        pos++
    }

    private fun safe(token: String): String =
        token.take(IDENT_PREVIEW).filter { it.isLetterOrDigit() || it in "_*()" }

    companion object {
        private const val IDENT_PREVIEW = 60

        /** Hard cap on grouping/`not` nesting; well past any real Sigma condition, far below a stack overflow. */
        private const val MAX_DEPTH = 64

        /** Coarse pre-tokenize bounds so a pathological condition is rejected before any recursion. */
        private const val MAX_CONDITION_LENGTH = 4_096
        private const val MAX_PARENS = 128

        private val STRUCTURAL = setOf("(", ")")
        private val KEYWORDS = setOf("and", "or", "not", "of")

        /** Splits a condition into identifier, keyword and parenthesis tokens. */
        private fun tokenize(condition: String): List<String> {
            val out = mutableListOf<String>()
            val sb = StringBuilder()
            fun flush() {
                if (sb.isNotEmpty()) {
                    out += sb.toString()
                    sb.clear()
                }
            }
            for (ch in condition) {
                when {
                    ch.isWhitespace() -> flush()
                    ch == '(' || ch == ')' -> {
                        flush()
                        out += ch.toString()
                    }
                    ch.isLetterOrDigit() || ch == '_' || ch == '*' || ch == '-' || ch == '.' -> sb.append(ch)
                    else -> throw SigmaImportException(
                        "Sigma condition contains an unsupported character '$ch'"
                    )
                }
            }
            flush()
            return out
        }

        /** Compiles a `*`-only Sigma selection pattern to an anchored regex over selection names. */
        private fun patternToRegex(pattern: String): Regex {
            val escaped = pattern.split("*").joinToString(".*") { Regex.escape(it) }
            return Regex("^$escaped$")
        }
    }
}
