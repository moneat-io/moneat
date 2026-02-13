import {createFileRoute} from '@tanstack/react-router'
import {DocPage, DocSection, DocParagraph} from '@/components/docs/doc-page'
import {Callout} from '@/components/docs/callout'

export const Route = createFileRoute('/docs/issue-tracking')({
  component: IssueTrackingPage,
})

function IssueTrackingPage() {
  return (
    <DocPage
      title="Issue Tracking"
      description="Group, triage, and resolve errors with automatic issue management."
    >
      <DocSection title="How Issues Work">
        <DocParagraph>
          When Moneat receives an error event, it generates a fingerprint and groups the event with other events
          that share the same fingerprint. This group is called an <strong className="text-foreground">issue</strong>.
          Each issue tracks the number of occurrences, affected users, first and last seen timestamps, and the
          full event history.
        </DocParagraph>
      </DocSection>

      <DocSection title="Issue Lifecycle">
        <DocParagraph>
          Issues move through the following states as your team triages and resolves them:
        </DocParagraph>
        <div className="space-y-3">
          <div className="flex items-start gap-3 p-3 rounded-lg border">
            <span className="px-2 py-0.5 rounded text-xs font-medium bg-yellow-500/10 text-yellow-500 shrink-0">New</span>
            <p className="text-sm text-muted-foreground">
              The issue has just been created. It hasn't been seen or addressed by any team member yet.
            </p>
          </div>
          <div className="flex items-start gap-3 p-3 rounded-lg border">
            <span className="px-2 py-0.5 rounded text-xs font-medium bg-blue-500/10 text-blue-500 shrink-0">Acknowledged</span>
            <p className="text-sm text-muted-foreground">
              A team member has seen the issue and is aware of it. This indicates the issue is being looked at.
            </p>
          </div>
          <div className="flex items-start gap-3 p-3 rounded-lg border">
            <span className="px-2 py-0.5 rounded text-xs font-medium bg-green-500/10 text-green-500 shrink-0">Resolved</span>
            <p className="text-sm text-muted-foreground">
              The underlying problem has been fixed. If the same error occurs again, the issue will be reopened
              automatically.
            </p>
          </div>
          <div className="flex items-start gap-3 p-3 rounded-lg border">
            <span className="px-2 py-0.5 rounded text-xs font-medium bg-zinc-500/10 text-zinc-500 shrink-0">Ignored</span>
            <p className="text-sm text-muted-foreground">
              The issue has been intentionally ignored. It won't trigger alerts or appear in the default view.
              Useful for known, non-critical issues.
            </p>
          </div>
        </div>
      </DocSection>

      <DocSection title="Issue Details">
        <DocParagraph>
          Clicking on an issue in the dashboard opens the detail view, which includes:
        </DocParagraph>
        <ul className="list-disc list-inside space-y-2 text-sm text-muted-foreground">
          <li><strong className="text-foreground">Stack trace</strong> — Full exception stack trace with source context</li>
          <li><strong className="text-foreground">Breadcrumbs</strong> — Timeline of events leading up to the error</li>
          <li><strong className="text-foreground">Tags</strong> — Filterable metadata (environment, browser, OS, etc.)</li>
          <li><strong className="text-foreground">User impact</strong> — Number of unique users affected</li>
          <li><strong className="text-foreground">Event history</strong> — Timeline of all occurrences with frequency chart</li>
          <li><strong className="text-foreground">Device &amp; browser</strong> — Client environment information</li>
        </ul>
      </DocSection>

      <DocSection title="Filtering & Search">
        <DocParagraph>
          The issues list supports several filtering options to help you focus on what matters:
        </DocParagraph>
        <ul className="list-disc list-inside space-y-2 text-sm text-muted-foreground">
          <li><strong className="text-foreground">Status</strong> — Filter by new, acknowledged, resolved, or ignored</li>
          <li><strong className="text-foreground">Project</strong> — Show issues from specific projects</li>
          <li><strong className="text-foreground">Time range</strong> — Filter by when issues were first or last seen</li>
          <li><strong className="text-foreground">Sort</strong> — Sort by last seen, first seen, event count, or user count</li>
        </ul>
      </DocSection>

      <DocSection title="Auto-Reopening">
        <Callout variant="info" title="Resolved issues can reopen">
          When you resolve an issue, Moneat continues to monitor for new events matching that fingerprint.
          If the same error occurs again, the issue is automatically reopened and you'll be notified, ensuring
          regressions don't go unnoticed.
        </Callout>
      </DocSection>
    </DocPage>
  )
}
