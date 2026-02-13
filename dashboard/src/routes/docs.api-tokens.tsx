import {createFileRoute} from '@tanstack/react-router'
import {DocPage, DocSection, DocSubSection, DocParagraph} from '@/components/docs/doc-page'
import {CodeBlock, InlineCode} from '@/components/docs/code-block'
import {Callout} from '@/components/docs/callout'
import {StepList} from '@/components/docs/step-list'

export const Route = createFileRoute('/docs/api-tokens')({
  component: ApiTokensPage,
})

function ApiTokensPage() {
  return (
    <DocPage
      title="API Tokens"
      description="Create and manage API tokens for programmatic access to Moneat's API."
    >
      <DocSection title="Overview">
        <DocParagraph>
          API tokens let you interact with Moneat's API programmatically. Use them in CI/CD pipelines
          to create releases, upload source maps, or build custom integrations. Tokens are scoped —
          you control exactly what each token can access.
        </DocParagraph>
      </DocSection>

      <DocSection title="Creating a Token">
        <StepList
          steps={[
            {
              title: 'Navigate to token settings',
              content: 'Go to Settings → API Tokens in the dashboard.',
            },
            {
              title: 'Create a new token',
              content: 'Click "Create Token" and provide a descriptive name (e.g., "CI/CD Pipeline", "Release Automation").',
            },
            {
              title: 'Select scopes',
              content: 'Choose the permissions your token needs. Use the principle of least privilege — only grant the scopes that are actually required.',
            },
            {
              title: 'Copy and save',
              content: 'Copy the generated token immediately. For security, the full token value is only shown once and cannot be retrieved later.',
            },
          ]}
        />
        <Callout variant="warning" title="Save your token">
          The token is only displayed once when created. Store it securely in your CI/CD secrets
          or a password manager. If you lose it, you'll need to create a new token.
        </Callout>
      </DocSection>

      <DocSection title="Token Scopes">
        <DocParagraph>
          Scopes define what a token can do. Available scopes include:
        </DocParagraph>
        <div className="space-y-2">
          <div className="flex items-start gap-3 p-3 rounded border text-sm">
            <InlineCode>releases:read</InlineCode>
            <span className="text-muted-foreground">— View releases and their details</span>
          </div>
          <div className="flex items-start gap-3 p-3 rounded border text-sm">
            <InlineCode>releases:write</InlineCode>
            <span className="text-muted-foreground">— Create, update, and finalize releases; upload source maps</span>
          </div>
        </div>
        <DocParagraph>
          Additional scopes may be added as new API features are released.
        </DocParagraph>
      </DocSection>

      <DocSection title="Using Tokens">
        <DocSubSection title="API Authentication">
          <DocParagraph>
            Include your token in the <InlineCode>Authorization</InlineCode> header with the <InlineCode>Bearer</InlineCode> scheme:
          </DocParagraph>
          <CodeBlock
            language="bash"
            code={`curl -H "Authorization: Bearer <your_token>" \\
  https://api.moneat.io/api/0/organizations/{org}/releases/`}
          />
        </DocSubSection>

        <DocSubSection title="With sentry-cli">
          <DocParagraph>
            Set the <InlineCode>SENTRY_AUTH_TOKEN</InlineCode> environment variable when using <InlineCode>sentry-cli</InlineCode>:
          </DocParagraph>
          <CodeBlock
            language="bash"
            code={`export SENTRY_URL=https://api.moneat.io
export SENTRY_AUTH_TOKEN=<your_token>
export SENTRY_ORG=<your_org>

sentry-cli releases new 1.0.0`}
          />
        </DocSubSection>
      </DocSection>

      <DocSection title="Managing Tokens">
        <DocParagraph>
          From the API Tokens settings page, you can:
        </DocParagraph>
        <ul className="list-disc list-inside space-y-1 text-sm text-muted-foreground">
          <li><strong className="text-foreground">Rename</strong> — Update the token's name for better organization</li>
          <li><strong className="text-foreground">Update scopes</strong> — Add or remove permissions</li>
          <li><strong className="text-foreground">Revoke</strong> — Permanently disable a token. This is immediate and cannot be undone.</li>
        </ul>
        <Callout variant="danger" title="Compromised tokens">
          If you suspect a token has been compromised, revoke it immediately and create a new one.
          Revocation takes effect instantly across all API requests.
        </Callout>
      </DocSection>
    </DocPage>
  )
}
