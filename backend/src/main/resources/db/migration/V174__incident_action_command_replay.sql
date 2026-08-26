-- Preserve action resource IDs when incident action commands are replayed.
ALTER TABLE native_incident_commands
    ADD COLUMN action_resource_id UUID;
