// Moneat - Mobile-First Error Monitoring Platform
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
import {CodeBlock, InlineCode} from '@/components/docs/code-block'
import {Callout} from '@/components/docs/callout'

export const Route = createFileRoute('/docs/releases')({
  component: ReleasesPage,
})

function ReleasesPage() {
  return (
    <DocPage
      title="Releases & Source Maps"
      description="Track deployments, associate errors with releases, and upload source maps for readable stack traces."
    >
      <DocSection title="Overview">
        <DocParagraph>
          Releases let you track which version of your code is deployed and correlate errors with
          specific deployments. When you create a release in Moneat, errors captured by the SDK are
          automatically tagged with the release version, giving you visibility into which deployments
          introduced new issues.
        </DocParagraph>
      </DocSection>

      <DocSection title="Creating Releases">
        <DocParagraph>
          Create releases using the Sentry-compatible release API. This is typically done in your CI/CD
          pipeline after a successful build.
        </DocParagraph>
        <CodeBlock
          title="Create a Release (cURL)"
          language="bash"
          code={`curl -X POST https://api.moneat.io/api/0/organizations/{org_slug}/releases/ \\
  -H "Authorization: Bearer <auth_token>" \\
  -H "Content-Type: application/json" \\
  -d '{
    "version": "1.2.3",
    "projects": ["my-project"]
  }'`}
        />
        <DocParagraph>
          You can also use the <InlineCode>sentry-cli</InlineCode> tool, which works with Moneat out of the box.
          Just set the <InlineCode>SENTRY_URL</InlineCode> environment variable to your Moneat instance:
        </DocParagraph>
        <CodeBlock
          title="Using sentry-cli"
          language="bash"
          code={`export SENTRY_URL=https://api.moneat.io
export SENTRY_AUTH_TOKEN=<your_auth_token>
export SENTRY_ORG=<your_org_slug>

sentry-cli releases new 1.2.3
sentry-cli releases set-commits 1.2.3 --auto
sentry-cli releases finalize 1.2.3`}
        />
      </DocSection>

      <DocSection title="Source Maps">
        <DocSubSection title="Why Source Maps?">
          <DocParagraph>
            JavaScript and other transpiled/minified languages produce unreadable stack traces in
            production. Source maps allow Moneat to map minified code back to your original source,
            giving you readable file names, line numbers, and code context in error reports.
          </DocParagraph>
        </DocSubSection>

        <DocSubSection title="Uploading Source Maps">
          <DocParagraph>
            Upload source maps as part of your release process. Use the release API or <InlineCode>sentry-cli</InlineCode>:
          </DocParagraph>
          <CodeBlock
            title="Upload with sentry-cli"
            language="bash"
            code={`sentry-cli releases files 1.2.3 upload-sourcemaps ./dist \\
  --url-prefix '~/static/js'`}
          />
          <DocParagraph>
            Or upload via the API directly:
          </DocParagraph>
          <CodeBlock
            title="Upload via API (cURL)"
            language="bash"
            code={`curl -X POST https://api.moneat.io/api/0/organizations/{org_slug}/releases/1.2.3/files/ \\
  -H "Authorization: Bearer <auth_token>" \\
  -F file=@./dist/app.js.map \\
  -F name="~/static/js/app.js.map"`}
          />
        </DocSubSection>

        <Callout variant="tip" title="Build tool plugins">
          Most build tools have Sentry plugins that automatically upload source maps during the build
          process. Look for <InlineCode>@sentry/webpack-plugin</InlineCode>, <InlineCode>@sentry/vite-plugin</InlineCode>,
          or <InlineCode>@sentry/rollup-plugin</InlineCode> for seamless integration.
        </Callout>
      </DocSection>

      <DocSection title="Release Tracking">
        <DocParagraph>
          Once releases are created, you can view them in the <strong className="text-foreground">Releases</strong> section
          of the dashboard. Each release shows:
        </DocParagraph>
        <ul className="list-disc list-inside space-y-1 text-sm text-muted-foreground">
          <li><strong className="text-foreground">Version</strong> — The release identifier</li>
          <li><strong className="text-foreground">New issues</strong> — Issues first seen in this release</li>
          <li><strong className="text-foreground">Resolved issues</strong> — Issues resolved in this release</li>
          <li><strong className="text-foreground">Date</strong> — When the release was created and finalized</li>
        </ul>
      </DocSection>

      <DocSection title="SDK Configuration">
        <DocParagraph>
          Make sure your SDK is configured with the release version so events are tagged correctly:
        </DocParagraph>
        <CodeBlock
          language="javascript"
          code={`Sentry.init({
  dsn: "https://<key>@api.moneat.io/api/<project_id>",
  release: "1.2.3",
});`}
        />
      </DocSection>

      <DocSection title="Auth Token Scopes">
        <DocParagraph>
          API tokens used for release management need the following scopes:
        </DocParagraph>
        <ul className="list-disc list-inside space-y-1 text-sm text-muted-foreground">
          <li><InlineCode>releases:write</InlineCode> — Create and update releases</li>
          <li><InlineCode>releases:read</InlineCode> — List and view releases</li>
        </ul>
        <DocParagraph>
          See <a href="/docs/api-tokens" className="text-primary hover:underline">API Tokens</a> for how to create
          tokens with the right scopes.
        </DocParagraph>
      </DocSection>
    </DocPage>
  )
}
