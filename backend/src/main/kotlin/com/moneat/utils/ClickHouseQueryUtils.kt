package com.moneat.utils

/**
 * Utility for building ClickHouse WHERE clauses that work with demo mode.
 * 
 * Demo projects use negative IDs (-1, -2, -3) but ClickHouse columns are UInt64,
 * so direct comparison fails. This helper casts to Int64 when needed.
 */
object ClickHouseQueryUtils {
    
    /**
     * Generate a project_id comparison clause that works with negative IDs.
     * 
     * @param projectId The project ID to compare
     * @param columnName The column name (default: "project_id")
     * @return A WHERE clause fragment like "project_id = 123" or "toInt64(project_id) = -1"
     */
    fun projectIdClause(projectId: Long, columnName: String = "project_id"): String {
        return if (projectId < 0) {
            "toInt64($columnName) = $projectId"
        } else {
            "$columnName = $projectId"
        }
    }
    
    /**
     * Generate a timestamp retention clause that respects demo mode.
     * 
     * @param column The timestamp column name
     * @param retentionDays Number of days to retain
     * @param demoEpochMs Optional demo epoch in milliseconds (for frozen demo time)
     * @return A WHERE clause fragment like "timestamp >= now() - INTERVAL 90 DAY"
     */
    fun timestampRetentionClause(column: String, retentionDays: Int, demoEpochMs: Long? = null): String {
        val nowClause = if (demoEpochMs != null) {
            "toDateTime64(${demoEpochMs / 1000.0}, 3)"
        } else {
            "now()"
        }
        return "$column >= $nowClause - INTERVAL $retentionDays DAY"
    }
}
