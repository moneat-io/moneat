-- Versioned layered schedules preserve the existing single-rotation schedule contract.

CREATE TABLE on_call_schedule_layers (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    schedule_id INTEGER NOT NULL REFERENCES on_call_schedules(id) ON DELETE CASCADE,
    layer_order INTEGER NOT NULL,
    name VARCHAR(255) NOT NULL,
    rotation_type VARCHAR(20) NOT NULL,
    handoff_time TIME NOT NULL,
    timezone VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    explicit_gap BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_on_call_schedule_layers_resource UNIQUE (organization_id, resource_id),
    CONSTRAINT uq_on_call_schedule_layers_order UNIQUE (schedule_id, layer_order),
    CONSTRAINT chk_on_call_schedule_layers_order CHECK (layer_order >= 0)
);

CREATE INDEX idx_on_call_schedule_layers_schedule
    ON on_call_schedule_layers(organization_id, schedule_id, layer_order);

CREATE TABLE on_call_schedule_layer_participants (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    layer_id INTEGER NOT NULL REFERENCES on_call_schedule_layers(id) ON DELETE CASCADE,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_on_call_schedule_layer_participant_resource UNIQUE (organization_id, resource_id),
    CONSTRAINT uq_on_call_schedule_layer_participant_position UNIQUE (layer_id, position),
    CONSTRAINT uq_on_call_schedule_layer_participant_user UNIQUE (layer_id, user_id),
    CONSTRAINT chk_on_call_schedule_layer_participant_position CHECK (position >= 0)
);

CREATE INDEX idx_on_call_schedule_layer_participants_layer
    ON on_call_schedule_layer_participants(organization_id, layer_id, position);
