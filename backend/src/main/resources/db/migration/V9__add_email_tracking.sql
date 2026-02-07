-- Add email tracking table to monitor email sending costs
CREATE TABLE IF NOT EXISTS emails_sent (
    id INTEGER PRIMARY KEY AUTO_INCREMENT,
    organization_id INTEGER REFERENCES organizations(id),
    email_type VARCHAR(50) NOT NULL,
    recipient VARCHAR(255) NOT NULL,
    sent_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    success BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE INDEX idx_emails_sent_at ON emails_sent(sent_at);
CREATE INDEX idx_emails_type ON emails_sent(email_type);
CREATE INDEX idx_emails_org_id ON emails_sent(organization_id);
