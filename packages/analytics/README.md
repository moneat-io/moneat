# @moneat/analytics

Privacy-focused web analytics tracking script for [Moneat](https://moneat.io). Cookie-free, respects Do Not Track, and filters bots automatically.

## Installation

### npm

```bash
npm install @moneat/analytics
```

### Script tag

Serve `dist/m.js` from your Moneat instance and add it to your HTML:

```html
<script
  defer
  data-domain="myapp.com"
  data-key="YOUR_PROJECT_KEY"
  src="https://your-moneat-instance.com/js/m.js"
></script>
```

Custom events are then available via the global:

```js
window.moneat.track('Signup', { plan: 'pro' });
```

## Usage (npm)

```ts
import { init, trackEvent } from '@moneat/analytics';

init({
  domain: 'myapp.com',
  apiHost: 'https://your-moneat-instance.com',
  key: 'YOUR_PROJECT_KEY',
});

// Track a custom event with optional properties
trackEvent('Signup', { plan: 'pro' });
```

`init` automatically sends a pageview on load and tracks SPA navigations (History API + `popstate`) by default.

## Configuration

| Option | Type | Default | Description |
|---|---|---|---|
| `domain` | `string` | — | Your site's domain (e.g. `myapp.com`) |
| `apiHost` | `string` | — | Base URL of your Moneat instance |
| `key` | `string` | — | Project public key from your Moneat dashboard |
| `trackSpa` | `boolean` | `true` | Auto-track client-side navigations |
| `trackLocalhost` | `boolean` | `false` | Track events on `localhost` / `127.0.0.1` |
| `hashMode` | `boolean` | `false` | Use hash-based routing (`#/path`) instead of History API |

## Privacy

- **No cookies** — sessions are not persisted across visits
- **Do Not Track** — honoured; cannot be overridden
- **Bot filtering** — Phantom, Nightmare, Puppeteer, and `navigator.webdriver` are ignored automatically

## Building

```bash
npm run build   # Produces dist/analytics.esm.js, dist/analytics.cjs.js, dist/m.js
npm run dev     # Watch mode
```

## License

MIT
