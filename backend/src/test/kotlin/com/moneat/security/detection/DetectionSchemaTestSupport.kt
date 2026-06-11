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

import com.moneat.security.signals.SecuritySignalEvidence
import com.moneat.security.signals.SecuritySignals
import com.moneat.shared.models.Organizations
import com.moneat.testsupport.TestDatabaseHelper
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Manual H2 DDL for the detection-rule tables (and the signals tables the evaluators write into).
 * SchemaUtils cannot create them directly because Exposed emits JSONB column defaults (`[]` / `{}`)
 * unquoted, which H2 rejects — the same reason P1a's [com.moneat.security.signals.SignalSchemaTestSupport]
 * and the workflow service test use hand-written DDL. Columns mirror `V120__detection_rules.sql` and
 * `V119__security_signals.sql` with JSONB → TEXT for H2; the Postgres partial unique index on signals
 * is omitted (H2 cannot express it) exactly as P1a documents.
 */
object DetectionSchemaTestSupport {

    fun reset() {
        TestDatabaseHelper.dropAndPatchJsonb(
            Organizations,
            DetectionRules,
            DetectionBaselineValues,
            SecuritySignals,
            SecuritySignalEvidence,
        )
        transaction {
            SchemaUtils.create(Organizations)
            // Signals tables (SignalWriter writes into these from the evaluators).
            exec(
                """
                CREATE TABLE security_signals (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    resource_id UUID NOT NULL DEFAULT RANDOM_UUID(),
                    organization_id INT NOT NULL,
                    source VARCHAR(32) NOT NULL,
                    rule_id VARCHAR(255) NOT NULL,
                    rule_name VARCHAR(255) NOT NULL,
                    severity VARCHAR(16) NOT NULL,
                    status VARCHAR(16) NOT NULL DEFAULT 'open',
                    archive_reason VARCHAR(16),
                    dedup_key TEXT NOT NULL,
                    entities TEXT NOT NULL DEFAULT '{}',
                    sample_count INT NOT NULL DEFAULT 1,
                    assignee_user_id INT,
                    tags TEXT NOT NULL DEFAULT '[]',
                    first_seen TIMESTAMP NOT NULL,
                    last_seen TIMESTAMP NOT NULL,
                    created_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL,
                    CONSTRAINT fk_security_signals_organization_id
                        FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            exec(
                "CREATE INDEX idx_security_signals_triage " +
                    "ON security_signals (organization_id, status, severity, last_seen)"
            )
            exec(
                """
                CREATE TABLE security_signal_evidence (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    resource_id UUID NOT NULL DEFAULT RANDOM_UUID(),
                    signal_id INT NOT NULL,
                    evidence_type VARCHAR(32) NOT NULL,
                    reference TEXT NOT NULL,
                    created_at TIMESTAMP NOT NULL,
                    CONSTRAINT fk_security_signal_evidence_signal
                        FOREIGN KEY (signal_id) REFERENCES security_signals(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            exec(
                "CREATE INDEX idx_security_signal_evidence_signal " +
                    "ON security_signal_evidence (signal_id, id)"
            )
            // Detection tables.
            exec(
                """
                CREATE TABLE detection_rules (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    resource_id UUID NOT NULL DEFAULT RANDOM_UUID(),
                    organization_id INT NOT NULL,
                    name VARCHAR(255) NOT NULL,
                    description TEXT NOT NULL DEFAULT '',
                    source VARCHAR(32) NOT NULL DEFAULT 'logs',
                    filter TEXT NOT NULL DEFAULT '',
                    group_by TEXT NOT NULL DEFAULT '[]',
                    window_seconds INT NOT NULL DEFAULT 300,
                    type VARCHAR(32) NOT NULL DEFAULT 'threshold',
                    threshold_count INT,
                    severity VARCHAR(16) NOT NULL DEFAULT 'medium',
                    signal_title TEXT NOT NULL DEFAULT '',
                    signal_message TEXT NOT NULL DEFAULT '',
                    suppressions TEXT NOT NULL DEFAULT '[]',
                    enabled BOOLEAN NOT NULL DEFAULT FALSE,
                    tags TEXT NOT NULL DEFAULT '[]',
                    created_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL,
                    CONSTRAINT fk_detection_rules_organization_id
                        FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            exec("CREATE INDEX idx_detection_rules_org ON detection_rules (organization_id, enabled)")
            exec(
                """
                CREATE TABLE detection_baseline_values (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    resource_id UUID NOT NULL DEFAULT RANDOM_UUID(),
                    rule_id INT NOT NULL,
                    organization_id INT NOT NULL,
                    group_key TEXT NOT NULL,
                    first_seen TIMESTAMP NOT NULL,
                    last_seen TIMESTAMP NOT NULL,
                    CONSTRAINT fk_detection_baseline_rule
                        FOREIGN KEY (rule_id) REFERENCES detection_rules(id) ON DELETE CASCADE,
                    CONSTRAINT fk_detection_baseline_organization_id
                        FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            exec(
                "CREATE UNIQUE INDEX idx_detection_baseline_unique " +
                    "ON detection_baseline_values (rule_id, group_key)"
            )
        }
    }
}
