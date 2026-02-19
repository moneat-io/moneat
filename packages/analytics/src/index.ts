/**
 * @moneat/analytics - Privacy-focused web analytics
 *
 * Usage:
 *   import { init, trackEvent } from '@moneat/analytics'
 *   init({ domain: 'myapp.com', apiHost: 'https://moneat.example.com', key: 'abc123' })
 *   trackEvent('Signup', { plan: 'pro' })
 */
export { init, trackEvent } from './tracker';
export type { TrackerConfig } from './tracker';
