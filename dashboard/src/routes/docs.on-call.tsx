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

export const Route = createFileRoute('/docs/on-call')({
  component: OnCallPage,
})

function OnCallPage() {
  return (
    <DocPage
      title="On-Call & Incidents"
      description="Set up on-call schedules, escalation policies, and manage incidents from alert to resolution."
    >
      <DocSection title="Overview">
        <DocParagraph>
          Moneat includes a built-in on-call and incident management system. When critical errors or uptime
          failures are detected, Moneat can automatically create incidents and notify the right people through
          configurable escalation policies. Notifications are sent via push notifications (mobile app), Slack
          DMs, and email.
        </DocParagraph>
      </DocSection>

      <DocSection title="On-Call Schedules">
        <DocSubSection title="Creating a Schedule">
          <DocParagraph>
            On-call schedules define who is responsible for responding to incidents at any given time. Navigate to
            <strong className="text-foreground"> On-Call → Schedules</strong> to create and manage schedules.
          </DocParagraph>
          <ul className="list-disc list-inside space-y-1 text-sm text-muted-foreground">
            <li>Name your schedule (e.g., "Backend Team Primary")</li>
            <li>Add team members as participants</li>
            <li>Configure rotation period (daily, weekly, or custom)</li>
            <li>Set rotation start time and timezone</li>
          </ul>
        </DocSubSection>

        <DocSubSection title="Rotations">
          <DocParagraph>
            Rotations automatically cycle through participants on the schedule. When the rotation period
            ends, the next person in the list becomes the primary on-call responder. You can set up
            multiple rotation layers for backup coverage.
          </DocParagraph>
        </DocSubSection>

        <DocSubSection title="Overrides">
          <DocParagraph>
            Need to swap shifts? Overrides let you temporarily assign a different person to be on-call
            for a specific time period without modifying the underlying rotation schedule.
          </DocParagraph>
        </DocSubSection>
      </DocSection>

      <DocSection title="Escalation Policies">
        <DocParagraph>
          Escalation policies define what happens when an incident is created. They consist of ordered steps
          that are executed in sequence until the incident is acknowledged.
        </DocParagraph>

        <DocSubSection title="Priority Levels">
          <DocParagraph>
            Incidents are assigned a priority level that determines how urgently they are escalated:
          </DocParagraph>
          <div className="space-y-2">
            {[
              {level: 'P0 - Critical', desc: 'Immediate escalation. Production is down.', color: 'text-red-500 bg-red-500/10'},
              {level: 'P1 - High', desc: 'Urgent. Major functionality impacted.', color: 'text-orange-500 bg-orange-500/10'},
              {level: 'P2 - Medium', desc: 'Important. Degraded service or workaround needed.', color: 'text-yellow-500 bg-yellow-500/10'},
              {level: 'P3 - Low', desc: 'Minor issue. No immediate user impact.', color: 'text-blue-500 bg-blue-500/10'},
              {level: 'P4 - Informational', desc: 'Tracked for awareness.', color: 'text-zinc-400 bg-zinc-500/10'},
            ].map((p) => (
              <div key={p.level} className="flex items-start gap-3 text-sm">
                <span className={`px-2 py-0.5 rounded text-xs font-medium shrink-0 ${p.color}`}>{p.level}</span>
                <span className="text-muted-foreground">{p.desc}</span>
              </div>
            ))}
          </div>
        </DocSubSection>

        <DocSubSection title="Escalation Steps">
          <DocParagraph>
            Each step in an escalation policy specifies who to notify and how long to wait before
            escalating to the next step. For example:
          </DocParagraph>
          <ol className="list-decimal list-inside space-y-1 text-sm text-muted-foreground">
            <li>Notify the primary on-call via push notification and Slack</li>
            <li>If not acknowledged within 5 minutes, notify the secondary on-call</li>
            <li>If still not acknowledged after 10 minutes, notify the engineering manager</li>
          </ol>
        </DocSubSection>

        <DocSubSection title="Business Hours">
          <DocParagraph>
            You can configure business hours for your team. Escalation policies can behave differently
            during and outside of business hours — for example, using less aggressive timeouts during
            working hours when people are already at their desks.
          </DocParagraph>
        </DocSubSection>
      </DocSection>

      <DocSection title="Incident Management">
        <DocSubSection title="Incident Lifecycle">
          <DocParagraph>
            Incidents follow a standard lifecycle:
          </DocParagraph>
          <div className="space-y-2">
            <div className="flex items-center gap-2 text-sm">
              <span className="h-2 w-2 rounded-full bg-red-500" />
              <strong className="text-foreground">Triggered</strong>
              <span className="text-muted-foreground">— Incident created, notifications being sent</span>
            </div>
            <div className="flex items-center gap-2 text-sm">
              <span className="h-2 w-2 rounded-full bg-yellow-500" />
              <strong className="text-foreground">Acknowledged</strong>
              <span className="text-muted-foreground">— A responder has acknowledged and is investigating</span>
            </div>
            <div className="flex items-center gap-2 text-sm">
              <span className="h-2 w-2 rounded-full bg-green-500" />
              <strong className="text-foreground">Resolved</strong>
              <span className="text-muted-foreground">— The incident has been resolved</span>
            </div>
          </div>
        </DocSubSection>

        <DocSubSection title="Incident Actions">
          <DocParagraph>
            While an incident is active, responders can:
          </DocParagraph>
          <ul className="list-disc list-inside space-y-1 text-sm text-muted-foreground">
            <li><strong className="text-foreground">Acknowledge</strong> — Stop escalation and indicate you're working on it</li>
            <li><strong className="text-foreground">Resolve</strong> — Mark the incident as resolved</li>
            <li><strong className="text-foreground">Reassign</strong> — Transfer the incident to another team member</li>
            <li><strong className="text-foreground">Add notes</strong> — Document findings and actions taken</li>
          </ul>
        </DocSubSection>

        <DocSubSection title="Timeline">
          <DocParagraph>
            Every incident has a full audit timeline showing all actions taken — when it was created,
            who was notified, when it was acknowledged, notes added, and when it was resolved. This
            is invaluable for post-incident reviews.
          </DocParagraph>
        </DocSubSection>
      </DocSection>

      <DocSection title="Notification Channels">
        <DocParagraph>
          Moneat supports multiple notification channels for on-call alerts:
        </DocParagraph>
        <ul className="list-disc list-inside space-y-2 text-sm text-muted-foreground">
          <li><strong className="text-foreground">Push Notifications</strong> — Via the Moneat mobile app (iOS & Android). Responders can acknowledge and resolve directly from the notification.</li>
          <li><strong className="text-foreground">Slack DMs</strong> — Direct messages with interactive buttons for acknowledge/resolve actions.</li>
          <li><strong className="text-foreground">Email</strong> — Notification emails with incident details and action links.</li>
        </ul>
        <Callout variant="tip" title="Mobile App">
          Install the Moneat mobile app to receive push notifications for on-call alerts.
          You can acknowledge and resolve incidents directly from your phone.
        </Callout>
      </DocSection>

      <DocSection title="Slack Usergroup Sync">
        <DocParagraph>
          You can link an on-call schedule to a Slack usergroup. When the on-call rotation changes,
          Moneat automatically updates the Slack usergroup membership so that <code className="px-1 py-0.5 rounded bg-muted text-sm font-mono">@on-call</code> always
          mentions the current responder.
        </DocParagraph>
      </DocSection>
    </DocPage>
  )
}
