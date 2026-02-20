# Security Policy

## Reporting a Vulnerability

If you discover a security vulnerability in Moneat, please report it responsibly.

**Do NOT open a public GitHub issue for security vulnerabilities.**

### How to Report

Email us at **support@moneat.io** with:

- A description of the vulnerability
- Steps to reproduce the issue
- The potential impact
- Any suggested fixes (optional)

### What to Expect

- **Acknowledgment** within 48 hours of your report
- **Status update** within 7 days with an assessment and expected timeline
- **Credit** in the security advisory (unless you prefer to remain anonymous)

### Scope

The following are in scope:

- Authentication and authorization bypasses
- SQL injection or other injection vulnerabilities
- Cross-site scripting (XSS)
- Cross-site request forgery (CSRF)
- Server-side request forgery (SSRF)
- Remote code execution
- Information disclosure of sensitive data
- Privilege escalation

### Out of Scope

- Denial of service (DoS/DDoS)
- Social engineering attacks
- Issues in third-party dependencies (report these upstream)
- Issues requiring physical access to a user's device

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| Latest  | :white_check_mark: |

## Security Best Practices for Self-Hosting

- Always change default secrets (`JWT_SECRET`, database passwords) before deploying
- Use HTTPS in production with valid TLS certificates
- Keep your Moneat instance updated to the latest version
- Restrict network access to database ports (PostgreSQL, ClickHouse, Redis)
- Review `ESSENTIAL_ENV_VARS.md` for all required configuration
