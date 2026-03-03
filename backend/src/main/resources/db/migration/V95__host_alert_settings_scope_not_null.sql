-- Normalize any existing NULL scope values to the default ('host')
UPDATE host_alert_settings SET scope = 'host' WHERE scope IS NULL;

-- Enforce NOT NULL with default 'host' on the scope column
ALTER TABLE host_alert_settings ALTER COLUMN scope SET NOT NULL;
ALTER TABLE host_alert_settings ALTER COLUMN scope SET DEFAULT 'host';
