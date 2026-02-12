CREATE TABLE on_call_schedule_usergroups (
    id SERIAL PRIMARY KEY,
    schedule_id INTEGER NOT NULL UNIQUE REFERENCES on_call_schedules(id) ON DELETE CASCADE,
    slack_usergroup_id VARCHAR(100) NOT NULL,
    slack_usergroup_handle VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
