ALTER TABLE alert_episodes
    ADD COLUMN title TEXT,
    ADD COLUMN description TEXT,
    ADD COLUMN priority VARCHAR(20);
