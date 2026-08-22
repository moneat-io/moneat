-- Versioned responder roles, assignments, participants, observers, and handovers.

CREATE TABLE native_incident_role_definitions (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    stable_key VARCHAR(100) NOT NULL,
    version INTEGER NOT NULL,
    is_current BOOLEAN NOT NULL DEFAULT TRUE,
    name VARCHAR(120) NOT NULL,
    description TEXT,
    responsibilities TEXT NOT NULL,
    private_instructions TEXT,
    is_required BOOLEAN NOT NULL DEFAULT FALSE,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    created_by INTEGER NOT NULL REFERENCES users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    superseded_at TIMESTAMP WITH TIME ZONE,
    UNIQUE (organization_id, resource_id),
    UNIQUE (organization_id, stable_key, version),
    CONSTRAINT chk_native_incident_role_version CHECK (version > 0),
    CONSTRAINT chk_native_incident_role_key CHECK (stable_key <> '')
);

CREATE UNIQUE INDEX uq_native_incident_role_definitions_current
    ON native_incident_role_definitions(organization_id, stable_key)
    WHERE is_current;

CREATE TABLE native_incident_role_assignments (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    incident_id INTEGER NOT NULL REFERENCES on_call_incidents(id) ON DELETE CASCADE,
    role_definition_id INTEGER NOT NULL REFERENCES native_incident_role_definitions(id),
    assignee_user_id INTEGER NOT NULL REFERENCES users(id),
    assigned_by INTEGER NOT NULL REFERENCES users(id),
    assigned_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    ended_by INTEGER REFERENCES users(id),
    ended_at TIMESTAMP WITH TIME ZONE,
    end_reason VARCHAR(32),
    UNIQUE (organization_id, resource_id),
    CONSTRAINT chk_native_incident_assignment_end CHECK (
        (ended_at IS NULL AND ended_by IS NULL AND end_reason IS NULL) OR
        (ended_at IS NOT NULL AND ended_by IS NOT NULL AND end_reason IS NOT NULL)
    ),
    CONSTRAINT chk_native_incident_assignment_reason CHECK (
        end_reason IS NULL OR end_reason IN ('REASSIGNED', 'UNASSIGNED', 'HANDOVER', 'INCIDENT_ENDED')
    )
);

CREATE UNIQUE INDEX uq_native_incident_role_assignment_active
    ON native_incident_role_assignments(incident_id, role_definition_id)
    WHERE ended_at IS NULL;

CREATE INDEX idx_native_incident_role_assignment_user
    ON native_incident_role_assignments(assignee_user_id, ended_at);

CREATE TABLE native_incident_participants (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    incident_id INTEGER NOT NULL REFERENCES on_call_incidents(id) ON DELETE CASCADE,
    user_id INTEGER NOT NULL REFERENCES users(id),
    participation_type VARCHAR(24) NOT NULL,
    joined_by INTEGER NOT NULL REFERENCES users(id),
    joined_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    left_by INTEGER REFERENCES users(id),
    left_at TIMESTAMP WITH TIME ZONE,
    UNIQUE (organization_id, resource_id),
    CONSTRAINT chk_native_incident_participation_type CHECK (
        participation_type IN ('PARTICIPANT', 'OBSERVER')
    ),
    CONSTRAINT chk_native_incident_participant_leave CHECK (
        (left_at IS NULL AND left_by IS NULL) OR (left_at IS NOT NULL AND left_by IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_native_incident_participant_active
    ON native_incident_participants(incident_id, user_id)
    WHERE left_at IS NULL;

CREATE TABLE native_incident_handovers (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    incident_id INTEGER NOT NULL REFERENCES on_call_incidents(id) ON DELETE CASCADE,
    role_definition_id INTEGER NOT NULL REFERENCES native_incident_role_definitions(id),
    from_assignment_id INTEGER NOT NULL REFERENCES native_incident_role_assignments(id),
    to_assignment_id INTEGER NOT NULL REFERENCES native_incident_role_assignments(id),
    handed_over_by INTEGER NOT NULL REFERENCES users(id),
    note TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE (organization_id, resource_id)
);

CREATE INDEX idx_native_incident_handovers_incident
    ON native_incident_handovers(incident_id, created_at DESC);

