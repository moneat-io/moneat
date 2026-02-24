-- Custom dashboards: store user-created dashboard definitions and widget configurations

CREATE TABLE dashboards (
    id            BIGSERIAL PRIMARY KEY,
    org_id        BIGINT NOT NULL REFERENCES organizations(id),
    project_id    BIGINT REFERENCES projects(id),
    title         VARCHAR(255) NOT NULL,
    description   TEXT,
    layout_type   VARCHAR(20) NOT NULL DEFAULT 'grid',
    is_default    BOOLEAN NOT NULL DEFAULT false,
    created_by    BIGINT NOT NULL REFERENCES users(id),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE dashboard_widgets (
    id              BIGSERIAL PRIMARY KEY,
    dashboard_id    BIGINT NOT NULL REFERENCES dashboards(id) ON DELETE CASCADE,
    title           VARCHAR(255),
    widget_type     VARCHAR(50) NOT NULL,
    grid_x          INT NOT NULL DEFAULT 0,
    grid_y          INT NOT NULL DEFAULT 0,
    grid_w          INT NOT NULL DEFAULT 6,
    grid_h          INT NOT NULL DEFAULT 4,
    query_config    JSONB NOT NULL,
    display_config  JSONB NOT NULL DEFAULT '{}',
    sort_order      INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_dashboards_org_id ON dashboards(org_id);
CREATE INDEX idx_dashboards_project_id ON dashboards(project_id);
CREATE INDEX idx_dashboard_widgets_dashboard_id ON dashboard_widgets(dashboard_id);
