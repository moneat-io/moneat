-- Versioned incident types, custom fields, and stage-specific forms.

CREATE TABLE native_incident_types (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    stable_key VARCHAR(100) NOT NULL,
    version INTEGER NOT NULL,
    is_current BOOLEAN NOT NULL DEFAULT TRUE,
    name VARCHAR(120) NOT NULL,
    description TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by INTEGER NOT NULL REFERENCES users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    superseded_at TIMESTAMP WITH TIME ZONE,
    UNIQUE (organization_id, resource_id),
    UNIQUE (organization_id, stable_key, version),
    CONSTRAINT chk_native_incident_type_version CHECK (version > 0),
    CONSTRAINT chk_native_incident_type_key CHECK (stable_key <> '')
);

CREATE UNIQUE INDEX uq_native_incident_types_current
    ON native_incident_types(organization_id, stable_key)
    WHERE is_current;

CREATE TABLE native_incident_custom_fields (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    stable_key VARCHAR(100) NOT NULL,
    version INTEGER NOT NULL,
    is_current BOOLEAN NOT NULL DEFAULT TRUE,
    name VARCHAR(160) NOT NULL,
    description TEXT,
    value_type VARCHAR(32) NOT NULL,
    catalog_resource_type VARCHAR(120),
    created_by INTEGER NOT NULL REFERENCES users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    superseded_at TIMESTAMP WITH TIME ZONE,
    UNIQUE (organization_id, resource_id),
    UNIQUE (organization_id, stable_key, version),
    CONSTRAINT chk_native_incident_field_version CHECK (version > 0),
    CONSTRAINT chk_native_incident_field_key CHECK (stable_key <> ''),
    CONSTRAINT chk_native_incident_field_value_type CHECK (
        value_type IN (
            'SELECT', 'MULTI_SELECT', 'TEXT', 'NUMBER', 'LINK',
            'USER', 'TEAM', 'SERVICE', 'CATALOG_RESOURCE'
        )
    ),
    CONSTRAINT chk_native_incident_field_catalog_type CHECK (
        (value_type = 'CATALOG_RESOURCE' AND catalog_resource_type IS NOT NULL) OR
        (value_type <> 'CATALOG_RESOURCE' AND catalog_resource_type IS NULL)
    )
);

CREATE UNIQUE INDEX uq_native_incident_custom_fields_current
    ON native_incident_custom_fields(organization_id, stable_key)
    WHERE is_current;

CREATE TABLE native_incident_custom_field_options (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    custom_field_id INTEGER NOT NULL REFERENCES native_incident_custom_fields(id) ON DELETE CASCADE,
    value VARCHAR(160) NOT NULL,
    label VARCHAR(160) NOT NULL,
    position INTEGER NOT NULL,
    color VARCHAR(32),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE (custom_field_id, resource_id),
    UNIQUE (custom_field_id, value),
    UNIQUE (custom_field_id, position),
    CONSTRAINT chk_native_incident_field_option_position CHECK (position >= 0)
);

CREATE TABLE native_incident_forms (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    incident_type_id INTEGER REFERENCES native_incident_types(id) ON DELETE CASCADE,
    stage VARCHAR(32) NOT NULL,
    version INTEGER NOT NULL,
    is_current BOOLEAN NOT NULL DEFAULT TRUE,
    name VARCHAR(160) NOT NULL,
    created_by INTEGER NOT NULL REFERENCES users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    superseded_at TIMESTAMP WITH TIME ZONE,
    UNIQUE (organization_id, resource_id),
    CONSTRAINT chk_native_incident_form_stage CHECK (
        stage IN ('DECLARATION', 'ACCEPTANCE', 'UPDATE', 'RESOLUTION', 'ESCALATION')
    ),
    CONSTRAINT chk_native_incident_form_version CHECK (version > 0)
);

CREATE UNIQUE INDEX uq_native_incident_forms_version
    ON native_incident_forms(
        organization_id,
        COALESCE(incident_type_id, 0),
        stage,
        version
    );

CREATE UNIQUE INDEX uq_native_incident_forms_current
    ON native_incident_forms(
        organization_id,
        COALESCE(incident_type_id, 0),
        stage
    )
    WHERE is_current;

CREATE TABLE native_incident_form_fields (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    form_id INTEGER NOT NULL REFERENCES native_incident_forms(id) ON DELETE CASCADE,
    custom_field_id INTEGER NOT NULL REFERENCES native_incident_custom_fields(id),
    position INTEGER NOT NULL,
    is_visible BOOLEAN NOT NULL DEFAULT TRUE,
    is_required BOOLEAN NOT NULL DEFAULT FALSE,
    default_value JSONB,
    help_text TEXT,
    condition_expression JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE (form_id, resource_id),
    UNIQUE (form_id, custom_field_id),
    UNIQUE (form_id, position),
    CONSTRAINT chk_native_incident_form_field_position CHECK (position >= 0),
    CONSTRAINT chk_native_incident_form_field_required CHECK (NOT is_required OR is_visible)
);

CREATE TABLE native_incident_form_submissions (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    incident_id INTEGER NOT NULL REFERENCES on_call_incidents(id) ON DELETE CASCADE,
    form_id INTEGER REFERENCES native_incident_forms(id) ON DELETE SET NULL,
    stage VARCHAR(32) NOT NULL,
    definition_snapshot JSONB NOT NULL,
    values_snapshot JSONB NOT NULL,
    submitted_by INTEGER NOT NULL REFERENCES users(id),
    submitted_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE (organization_id, resource_id),
    CONSTRAINT chk_native_incident_submission_stage CHECK (
        stage IN ('DECLARATION', 'ACCEPTANCE', 'UPDATE', 'RESOLUTION', 'ESCALATION')
    )
);

CREATE INDEX idx_native_incident_form_submissions_incident
    ON native_incident_form_submissions(incident_id, submitted_at DESC);

ALTER TABLE on_call_incidents
    ADD COLUMN incident_type_definition_id INTEGER
        REFERENCES native_incident_types(id) ON DELETE SET NULL,
    ADD COLUMN declaration_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb;

