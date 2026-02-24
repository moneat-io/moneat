-- Dashboard folders (flat, one level) and user favorites

CREATE TABLE dashboard_folders (
    id          BIGSERIAL PRIMARY KEY,
    org_id      BIGINT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name        VARCHAR(100) NOT NULL,
    color       VARCHAR(7),
    sort_order  INT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_dashboard_folders_org_id ON dashboard_folders(org_id);

ALTER TABLE dashboards
    ADD COLUMN folder_id BIGINT REFERENCES dashboard_folders(id) ON DELETE SET NULL;

CREATE INDEX idx_dashboards_folder_id ON dashboards(folder_id);

CREATE TABLE dashboard_favorites (
    user_id      INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    dashboard_id BIGINT NOT NULL REFERENCES dashboards(id) ON DELETE CASCADE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, dashboard_id)
);

CREATE INDEX idx_dashboard_favorites_user_id ON dashboard_favorites(user_id);
CREATE INDEX idx_dashboard_favorites_dashboard_id ON dashboard_favorites(dashboard_id);
