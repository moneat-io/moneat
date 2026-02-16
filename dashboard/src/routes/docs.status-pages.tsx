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
import {DocPage, DocSection, DocSubSection, DocParagraph} from '@/components/docs/doc-page'
import {Callout} from '@/components/docs/callout'

export const Route = createFileRoute('/docs/status-pages')({
  component: StatusPagesDoc,
})

function StatusPagesDoc() {
  return (
    <DocPage
      title="Status Pages"
      description="Create public status pages to communicate service health and incidents to your users."
    >
      <DocSection title="Overview">
        <DocParagraph>
          Status pages give your users a public view of your service health. When things go wrong,
          you can publish incidents to your status page so customers know what's happening and when
          to expect a resolution.
        </DocParagraph>
        <DocParagraph>
          Each status page gets a unique URL like <code className="px-1 py-0.5 rounded bg-muted text-sm font-mono">moneat.io/s/your-company</code> that
          you can share with your users or link from your website.
        </DocParagraph>
      </DocSection>

      <DocSection title="Creating a Status Page">
        <DocParagraph>
          Navigate to <strong className="text-foreground">Status Pages</strong> in the sidebar to create
          a new status page. You'll need to provide:
        </DocParagraph>
        <ul className="list-disc list-inside space-y-1 text-sm text-muted-foreground">
          <li><strong className="text-foreground">Name</strong> — Your company or service name</li>
          <li><strong className="text-foreground">Slug</strong> — The URL-friendly identifier (e.g., "your-company")</li>
          <li><strong className="text-foreground">Description</strong> — A brief description shown on the page</li>
        </ul>
      </DocSection>

      <DocSection title="Components">
        <DocSubSection title="Adding Components">
          <DocParagraph>
            Components represent the individual services or systems you want to display on your status
            page (e.g., "API", "Web App", "Database", "Payment Processing"). Each component shows its
            current status independently.
          </DocParagraph>
        </DocSubSection>

        <DocSubSection title="Component Status">
          <DocParagraph>
            Each component can have one of these statuses:
          </DocParagraph>
          <div className="space-y-2">
            <div className="flex items-center gap-3 text-sm">
              <span className="h-2.5 w-2.5 rounded-full bg-green-500" />
              <strong className="text-foreground">Operational</strong>
              <span className="text-muted-foreground">— Working normally</span>
            </div>
            <div className="flex items-center gap-3 text-sm">
              <span className="h-2.5 w-2.5 rounded-full bg-yellow-500" />
              <strong className="text-foreground">Degraded Performance</strong>
              <span className="text-muted-foreground">— Slower than usual but functional</span>
            </div>
            <div className="flex items-center gap-3 text-sm">
              <span className="h-2.5 w-2.5 rounded-full bg-orange-500" />
              <strong className="text-foreground">Partial Outage</strong>
              <span className="text-muted-foreground">— Some functionality affected</span>
            </div>
            <div className="flex items-center gap-3 text-sm">
              <span className="h-2.5 w-2.5 rounded-full bg-red-500" />
              <strong className="text-foreground">Major Outage</strong>
              <span className="text-muted-foreground">— Service is unavailable</span>
            </div>
          </div>
        </DocSubSection>

        <Callout variant="tip" title="Auto-update with monitors">
          Link uptime monitors to status page components. When a monitor detects downtime, the component
          status is automatically updated — no manual intervention needed.
        </Callout>
      </DocSection>

      <DocSection title="Publishing Incidents">
        <DocParagraph>
          When an issue affects your users, you can publish an incident to your status page. Incidents
          include:
        </DocParagraph>
        <ul className="list-disc list-inside space-y-1 text-sm text-muted-foreground">
          <li><strong className="text-foreground">Title</strong> — A brief summary of the issue</li>
          <li><strong className="text-foreground">Status</strong> — Investigating, Identified, Monitoring, or Resolved</li>
          <li><strong className="text-foreground">Affected components</strong> — Which services are impacted</li>
          <li><strong className="text-foreground">Updates</strong> — Post updates as the situation evolves</li>
        </ul>
        <DocParagraph>
          Published incidents are visible to anyone who visits your status page. Resolved incidents
          are kept in the history for transparency.
        </DocParagraph>
      </DocSection>

      <DocSection title="Public Access">
        <DocParagraph>
          Status pages are fully public and don't require authentication. You can share the URL
          directly with your customers, add it to your website footer, or link it in your support
          documentation.
        </DocParagraph>
        <Callout variant="info">
          Status pages are accessible at <code className="px-1 py-0.5 rounded bg-muted text-sm font-mono">moneat.io/s/your-slug</code>.
          The page is designed to be clean and professional, matching your organization's brand.
        </Callout>
      </DocSection>
    </DocPage>
  )
}
