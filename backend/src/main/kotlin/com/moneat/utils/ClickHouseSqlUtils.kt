package com.moneat.utils

/**
 * Comprehensive SQL escaping for ClickHouse string literals.
 * 
 * Escapes:
 * - Backslash (\)
 * - Single quote (')
 * - Null byte (\0)
 * - Newline (\n)
 * - Carriage return (\r)
 * - Tab (\t)
 * - Backspace (\b)
 * - Form feed (\f)
 * 
 * This prevents SQL injection when building ClickHouse queries via string interpolation.
 * 
 * IMPORTANT: Parameterized queries are always preferred when available.
 * Use this ONLY when building dynamic SQL that cannot be parameterized.
 */
object ClickHouseSqlUtils {
    
    /**
     * Escapes a string value for use in a ClickHouse SQL string literal.
     * Returns empty string if input is null.
     */
    fun escapeSql(value: String?): String {
        if (value == null) return ""
        
        return value
            .replace("\\", "\\\\")  // Must be first - escape backslashes
            .replace("'", "\\'")     // Escape single quotes
            .replace("\u0000", "\\0") // Null byte
            .replace("\n", "\\n")     // Newline
            .replace("\r", "\\r")     // Carriage return
            .replace("\t", "\\t")     // Tab
            .replace("\b", "\\b")     // Backspace
            .replace("\u000C", "\\f") // Form feed
    }
    
    /**
     * Escapes a string value for use in a ClickHouse LIKE pattern.
     * Escapes wildcards (% and _) in addition to standard SQL escaping.
     */
    fun escapeLikePattern(value: String?): String {
        if (value == null) return ""
        
        return escapeSql(value)
            .replace("%", "\\%")
            .replace("_", "\\_")
    }
    
    /**
     * Validates that a field name contains only safe characters (alphanumeric, underscore, dot).
     * Throws IllegalArgumentException if validation fails.
     * Use this to validate dynamic column/table names.
     */
    fun validateFieldName(fieldName: String) {
        require(fieldName.matches(Regex("^[a-zA-Z0-9_.]+$"))) {
            "Invalid field name: $fieldName. Only alphanumeric characters, underscores, and dots allowed."
        }
    }
    
    /**
     * Validates that an operator is in the allowlist of safe SQL operators.
     * Throws IllegalArgumentException if validation fails.
     */
    fun validateOperator(operator: String) {
        val allowedOperators = setOf(
            "=", "!=", "<>", "<", ">", "<=", ">=",
            "LIKE", "NOT LIKE", "ILIKE", "NOT ILIKE",
            "IN", "NOT IN",
            "IS NULL", "IS NOT NULL"
        )
        
        require(operator.uppercase() in allowedOperators) {
            "Invalid operator: $operator. Must be one of: ${allowedOperators.joinToString()}"
        }
    }
}
