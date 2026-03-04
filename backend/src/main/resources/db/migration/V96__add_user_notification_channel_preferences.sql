-- V96: User notification channel preferences and shift-change send dedup tracking

CREATE TABLE user_notification_channel_preferences (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    category TEXT NOT NULL,
    channel TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, category, channel),
    CONSTRAINT check_notification_pref_category
        CHECK (category IN ('high_urgency', 'low_urgency', 'shift_change')),
    CONSTRAINT check_notification_pref_channel
        CHECK (channel IN ('push', 'slack', 'email', 'sms', 'phone_call', 'discord'))
);

CREATE INDEX idx_user_notif_prefs_user
    ON user_notification_channel_preferences(user_id);

CREATE TABLE shift_change_notifications_sent (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    schedule_id INTEGER NOT NULL REFERENCES on_call_schedules(id) ON DELETE CASCADE,
    shift_start_at TIMESTAMP NOT NULL,
    channel TEXT NOT NULL,
    sent_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, schedule_id, shift_start_at, channel)
);
