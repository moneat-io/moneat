import {createFileRoute} from '@tanstack/react-router'
import {DocPage, DocSection, DocSubSection, DocParagraph} from '@/components/docs/doc-page'
import {Callout} from '@/components/docs/callout'

export const Route = createFileRoute('/docs/billing')({
  component: BillingPage,
})

function BillingPage() {
  return (
    <DocPage
      title="Billing & Plans"
      description="Understand plan tiers, usage-based billing, and account management."
    >
      <DocSection title="Pricing Model">
        <DocParagraph>
          Moneat uses a GB-based pricing model. Your usage is measured by the volume of event data
          ingested and stored. Each plan includes a monthly data allowance, and you can add additional
          capacity as needed.
        </DocParagraph>
      </DocSection>

      <DocSection title="Plan Tiers">
        <div className="space-y-4">
          <div className="p-4 rounded-lg border">
            <div className="flex items-center justify-between mb-2">
              <h3 className="font-semibold">Free</h3>
              <span className="text-sm text-muted-foreground">$0/month</span>
            </div>
            <p className="text-sm text-muted-foreground">
              Perfect for personal projects and evaluation. Includes basic error monitoring, limited
              event volume, and core features.
            </p>
          </div>

          <div className="p-4 rounded-lg border border-primary/30">
            <div className="flex items-center justify-between mb-2">
              <h3 className="font-semibold">Pro</h3>
              <span className="text-sm text-muted-foreground">Starting price/month</span>
            </div>
            <p className="text-sm text-muted-foreground">
              For growing teams. Higher event limits, longer data retention, and access to
              integrations and advanced features.
            </p>
          </div>

          <div className="p-4 rounded-lg border">
            <div className="flex items-center justify-between mb-2">
              <h3 className="font-semibold">Team</h3>
              <span className="text-sm text-muted-foreground">Custom pricing</span>
            </div>
            <p className="text-sm text-muted-foreground">
              For larger teams needing on-call management, SSO, and higher data volumes. Includes
              on-call seats and escalation policies.
            </p>
          </div>

          <div className="p-4 rounded-lg border">
            <div className="flex items-center justify-between mb-2">
              <h3 className="font-semibold">Business</h3>
              <span className="text-sm text-muted-foreground">Custom pricing</span>
            </div>
            <p className="text-sm text-muted-foreground">
              For organizations with high-volume needs. Extended retention, priority support, and
              dedicated infrastructure options.
            </p>
          </div>
        </div>

        <Callout variant="info">
          Visit the <a href="https://moneat.io" className="text-primary hover:underline font-medium">pricing page</a> for
          current pricing details and to compare plan features side-by-side.
        </Callout>
      </DocSection>

      <DocSection title="Usage Tracking">
        <DocParagraph>
          Monitor your data usage in real-time from <strong className="text-foreground">Settings &amp; Billing</strong>.
          The usage dashboard shows:
        </DocParagraph>
        <ul className="list-disc list-inside space-y-1 text-sm text-muted-foreground">
          <li><strong className="text-foreground">Current period usage</strong> — How much data you've ingested this billing cycle</li>
          <li><strong className="text-foreground">Usage breakdown</strong> — By project, event type, and day</li>
          <li><strong className="text-foreground">Quota remaining</strong> — How much capacity you have left</li>
          <li><strong className="text-foreground">Historical trends</strong> — Usage patterns over previous months</li>
        </ul>
      </DocSection>

      <DocSection title="On-Call Seats">
        <DocParagraph>
          On-call management is billed per seat. Each team member who participates in on-call
          schedules requires a seat. You can add or remove seats at any time from the billing
          settings.
        </DocParagraph>
      </DocSection>

      <DocSection title="Pay-As-You-Go">
        <DocSubSection title="Budget Limits">
          <DocParagraph>
            If you exceed your plan's included data, pay-as-you-go pricing applies. You can set a
            monthly budget limit to cap your spending. When the budget is reached, new events are
            dropped until the next billing cycle.
          </DocParagraph>
        </DocSubSection>
        <Callout variant="warning" title="Avoid surprises">
          Set a pay-as-you-go budget limit to prevent unexpected charges. You'll receive alerts
          as you approach your budget threshold.
        </Callout>
      </DocSection>

      <DocSection title="Managing Your Subscription">
        <DocParagraph>
          All billing operations are handled through <strong className="text-foreground">Settings &amp; Billing</strong>:
        </DocParagraph>
        <ul className="list-disc list-inside space-y-1 text-sm text-muted-foreground">
          <li>Upgrade or downgrade your plan</li>
          <li>Update payment method</li>
          <li>View and download invoices</li>
          <li>Adjust on-call seat count</li>
          <li>Set pay-as-you-go budget limits</li>
        </ul>
        <DocParagraph>
          Payment processing is handled securely through Stripe. Moneat does not store your credit
          card information directly.
        </DocParagraph>
      </DocSection>
    </DocPage>
  )
}
