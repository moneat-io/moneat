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

package com.moneat.security.signals

import com.moneat.shared.models.Organizations
import com.moneat.testsupport.TestDatabaseHelper
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Manual H2 DDL for the signals tables. SchemaUtils cannot create them directly because Exposed
 * emits the JSONB column defaults (`{}` / `[]`) unquoted, which H2 rejects — the same reason the
 * workflow tables use hand-written DDL in their service test. The columns mirror
 * `V119__security_signals.sql` with JSONB → TEXT for H2. The partial unique index is Postgres-only;
 * dedup correctness in tests rests on SignalWriter's in-transaction lookup.
 */
object SignalSchemaTestSupport {

    fun reset() {
        TestDatabaseHelper.dropAndPatchJsonb(
            Organizations,
            SecuritySignals,
            SecuritySignalEvidence,
            SecuritySignalAudit
        )
        transaction {
            SchemaUtils.create(Organizations)
            exec(
                """
                CREATE TABLE security_signals (
                    id INT AUTO_INCREMENT PRIMARY KEY,
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
            // V119's partial unique index idx_security_signals_open_dedup on
            // (organization_id, rule_id, dedup_key) WHERE status = 'open' is Postgres-only: H2
            // cannot express a filtered unique index, and a plain unique index would wrongly
            // forbid opening a fresh signal after the prior one is archived. Open-signal dedup is
            // enforced in the SignalWriter transaction (SELECT ... FOR UPDATE), which these tests
            // exercise; the production index is the concurrency backstop, verified on Postgres.
            exec(
                """
                CREATE TABLE security_signal_evidence (
                    id INT AUTO_INCREMENT PRIMARY KEY,
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
            exec(
                """
                CREATE TABLE security_signal_audit (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    signal_id INT NOT NULL,
                    organization_id INT NOT NULL,
                    actor_user_id INT,
                    action VARCHAR(32) NOT NULL,
                    from_status VARCHAR(16),
                    to_status VARCHAR(16),
                    reason VARCHAR(16),
                    note TEXT,
                    created_at TIMESTAMP NOT NULL,
                    CONSTRAINT fk_security_signal_audit_signal
                        FOREIGN KEY (signal_id) REFERENCES security_signals(id) ON DELETE CASCADE,
                    CONSTRAINT fk_security_signal_audit_organization_id
                        FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            exec(
                "CREATE INDEX idx_security_signal_audit_signal " +
                    "ON security_signal_audit (signal_id, id)"
            )
        }
    }
}
