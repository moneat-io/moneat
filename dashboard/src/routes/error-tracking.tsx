// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

import {createFileRoute} from '@tanstack/react-router'
import {Activity, Fingerprint, Layers, Search, Tag, Workflow} from 'lucide-react'
import {FeaturePageTemplate, type FeaturePageConfig} from '@/components/landing/FeaturePageTemplate'

const config: FeaturePageConfig = {
  slug: 'error-tracking',
  title: 'Error Tracking',
  tagline: 'Sentry SDK compatible',
  description: 'Catch, group, and triage errors with smart fingerprinting. See full stack traces, breadcrumbs, and user context for every exception across all your projects. Works with your existing Sentry SDKs.',
  metaDescription: 'Error tracking with smart fingerprinting, stack traces, and breadcrumbs. Compatible with Sentry SDKs. Start free with Moneat.',
  icon: Activity,
  iconColor: 'text-sky-400',
  iconBg: 'bg-sky-500/10',
  gradient: 'from-sky-500 to-cyan-400',
  accentColor: 'text-sky-400',
  screenshot: '/screenshots/error-tracking.png',
  screenshotAlt: 'Error tracking dashboard showing issues list with stack traces and context',
  subFeatures: [
    {icon: Fingerprint, title: 'Smart Fingerprinting', description: 'Automatically group similar errors together with intelligent fingerprinting that understands your stack.', iconColor: 'text-sky-400'},
    {icon: Layers, title: 'Stack Traces & Breadcrumbs', description: 'Full stack traces with source context, plus breadcrumbs showing what led up to each error.', iconColor: 'text-blue-400'},
    {icon: Search, title: 'Full-Text Search', description: 'Search across all your errors by message, user, tag, or any custom attribute you send.', iconColor: 'text-cyan-400'},
    {icon: Tag, title: 'Tags & Context', description: 'Attach user info, device details, custom tags, and structured context to every event.', iconColor: 'text-violet-400'},
    {icon: Workflow, title: 'Issue Workflow', description: 'Mark issues as resolved, ignored, or regressed. Get notified when resolved issues recur.', iconColor: 'text-amber-400'},
    {icon: Activity, title: 'Release Tracking', description: 'Track which releases introduced new errors and which resolved them.', iconColor: 'text-green-400'},
  ],
  compatNote: 'Compatible with Sentry SDKs for 100+ platforms. Just change your DSN and redeploy — no code changes required.',
}

export const Route = createFileRoute('/error-tracking')({
  component: () => <FeaturePageTemplate config={config} />,
})
