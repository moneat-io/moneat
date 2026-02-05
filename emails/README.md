# Moneat Email Templates

Email templates for Moneat error monitoring platform, built with [Maizzle](https://maizzle.com).

## Setup

```bash
npm install
```

## Development

Start the development server with live preview:

```bash
npm run dev
```

This will start a local server where you can preview email templates with sample data.

## Build

Build templates for production:

```bash
npm run build:production
```

This generates production-ready HTML emails in `build/templates/email/` with:
- Inlined CSS
- Purged unused styles
- Minified HTML
- Backend template placeholders (`{{ variable }}`)

## Templates

### error-alert.html

Sends notifications when new errors are detected.

**Variables:**
- `{{ issueTitle }}` - Error title/message
- `{{ issueLevel }}` - Severity level (error, warning, info)
- `{{ issueCulprit }}` - Location in code where error occurred
- `{{ issueMessage }}` - Full error message
- `{{ issueCount }}` - Number of occurrences
- `{{ issueUrl }}` - Link to issue in dashboard
- `{{ projectName }}` - Project name
- `{{ environment }}` - Environment (production, staging, etc)
- `{{ timestamp }}` - When error first occurred
- `{{ stackTrace }}` - Stack trace

## Customization

Edit `tailwind.config.js` to customize colors and styles. The Moneat brand colors are available:

```js
colors: {
  'moneat': {
    'error': '#ef4444',
    'warning': '#f59e0b',
    'info': '#3b82f6',
    'success': '#10b981',
    'primary': '#0f172a',
    'secondary': '#64748b',
  },
}
```

## Integration

Production builds output to `build/templates/email/` with `{{ }}` placeholders that should be replaced by your backend templating engine before sending emails.
