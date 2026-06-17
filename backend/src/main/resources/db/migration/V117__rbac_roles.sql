-- Cross-cutting RBAC (Enterprise feature: advanced_rbac). Domain-agnostic roles plus
-- user->role assignments, resolved through the PermissionBridge. A role holds a set of
-- namespaced "<resource>:<action>" permission keys owned by each domain (workflows,
-- incidents, ...); this storage stays generic. A user with at least one assignment is
-- governed by these granular grants; users with none fall back to the coarse org-role gates.

CREATE TABLE IF NOT EXISTS rbac_roles (
    id SERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name VARCHAR(120) NOT NULL,
    permissions JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_by INTEGER REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (organization_id, name)
);

CREATE TABLE IF NOT EXISTS rbac_role_assignments (
    id SERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    role_id INTEGER NOT NULL REFERENCES rbac_roles(id) ON DELETE CASCADE,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (organization_id, role_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_rbac_role_assignments_org_user
    ON rbac_role_assignments (organization_id, user_id);
