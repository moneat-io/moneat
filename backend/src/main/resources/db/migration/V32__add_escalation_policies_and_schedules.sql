-- V32: Escalation policies and on-call schedules

-- Escalation policies
CREATE TABLE escalation_policies (
    id SERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    repeat_count INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT check_repeat_count CHECK (repeat_count >= 0 AND repeat_count <= 10)
);

CREATE INDEX idx_escalation_policies_org ON escalation_policies(organization_id);

-- Escalation steps (ordered steps within a policy)
CREATE TABLE escalation_steps (
    id SERIAL PRIMARY KEY,
    escalation_policy_id INTEGER NOT NULL REFERENCES escalation_policies(id) ON DELETE CASCADE,
    step_order INTEGER NOT NULL,
    timeout_minutes INTEGER NOT NULL DEFAULT 5,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(escalation_policy_id, step_order),
    CONSTRAINT check_step_order CHECK (step_order >= 0),
    CONSTRAINT check_timeout CHECK (timeout_minutes > 0 AND timeout_minutes <= 1440)
);

CREATE INDEX idx_escalation_steps_policy ON escalation_steps(escalation_policy_id, step_order);

-- Escalation step targets (who to notify at each step)
CREATE TABLE escalation_step_targets (
    id SERIAL PRIMARY KEY,
    escalation_step_id INTEGER NOT NULL REFERENCES escalation_steps(id) ON DELETE CASCADE,
    target_type VARCHAR(20) NOT NULL,
    target_id INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT check_target_type CHECK (target_type IN ('USER', 'ON_CALL_SCHEDULE'))
);

CREATE INDEX idx_escalation_step_targets_step ON escalation_step_targets(escalation_step_id);
CREATE INDEX idx_escalation_step_targets_target ON escalation_step_targets(target_type, target_id);

-- On-call schedules
CREATE TABLE on_call_schedules (
    id SERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    rotation_type VARCHAR(20) NOT NULL,
    handoff_time TIME NOT NULL DEFAULT '09:00:00',
    timezone VARCHAR(100) NOT NULL DEFAULT 'UTC',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT check_rotation_type CHECK (rotation_type IN ('DAILY', 'WEEKLY', 'CUSTOM'))
);

CREATE INDEX idx_on_call_schedules_org ON on_call_schedules(organization_id);

-- On-call participants (ordered rotation members)
CREATE TABLE on_call_participants (
    id SERIAL PRIMARY KEY,
    schedule_id INTEGER NOT NULL REFERENCES on_call_schedules(id) ON DELETE CASCADE,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(schedule_id, user_id),
    UNIQUE(schedule_id, position),
    CONSTRAINT check_position CHECK (position >= 0)
);

CREATE INDEX idx_on_call_participants_schedule ON on_call_participants(schedule_id, position);
CREATE INDEX idx_on_call_participants_user ON on_call_participants(user_id);

-- On-call overrides (temporary coverage changes)
CREATE TABLE on_call_overrides (
    id SERIAL PRIMARY KEY,
    schedule_id INTEGER NOT NULL REFERENCES on_call_schedules(id) ON DELETE CASCADE,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    start_at TIMESTAMP NOT NULL,
    end_at TIMESTAMP NOT NULL,
    created_by INTEGER NOT NULL REFERENCES users(id),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT check_override_dates CHECK (end_at > start_at)
);

CREATE INDEX idx_on_call_overrides_schedule ON on_call_overrides(schedule_id);
CREATE INDEX idx_on_call_overrides_dates ON on_call_overrides(schedule_id, start_at, end_at);
CREATE INDEX idx_on_call_overrides_user ON on_call_overrides(user_id);
