// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

import {createFileRoute} from '@tanstack/react-router'
import {DocPage, DocSection, DocSubSection, DocParagraph} from '@/components/docs/doc-page'
import {Callout} from '@/components/docs/callout'
import {StepList} from '@/components/docs/step-list'

export const Route = createFileRoute('/docs/sso-authentication')({
  component: SsoAuthenticationPage,
})

function SsoAuthenticationPage() {
  return (
    <DocPage
      title="SSO & Authentication"
      description="Configure authentication providers including OAuth, SSO, and team invitations."
    >
      <DocSection title="Authentication Methods">
        <DocParagraph>
          Moneat supports multiple authentication methods so your team can sign in using their
          preferred identity provider:
        </DocParagraph>
        <ul className="list-disc list-inside space-y-2 text-sm text-muted-foreground">
          <li><strong className="text-foreground">Email &amp; Password</strong> — Standard email-based authentication with email verification</li>
          <li><strong className="text-foreground">GitHub OAuth</strong> — Sign in with your GitHub account</li>
          <li><strong className="text-foreground">Apple Sign In</strong> — Sign in with your Apple ID</li>
          <li><strong className="text-foreground">SSO</strong> — Enterprise single sign-on for organizations</li>
        </ul>
      </DocSection>

      <DocSection title="GitHub OAuth">
        <DocParagraph>
          GitHub OAuth lets your team sign in using their GitHub accounts. This is the easiest way
          to get started if your team already uses GitHub.
        </DocParagraph>
        <StepList
          steps={[
            {
              title: 'Click "Sign in with GitHub"',
              content: 'On the login or signup page, click the GitHub button.',
            },
            {
              title: 'Authorize Moneat',
              content: 'You\'ll be redirected to GitHub to authorize the Moneat application. Review the permissions and click "Authorize".',
            },
            {
              title: 'Complete onboarding',
              content: 'If this is your first time, you\'ll be guided through creating your organization and first project.',
            },
          ]}
        />
      </DocSection>

      <DocSection title="Apple Sign In">
        <DocParagraph>
          Apple Sign In is available on both the web dashboard and mobile app. It provides a
          privacy-focused authentication option with support for hidden email relay.
        </DocParagraph>
      </DocSection>

      <DocSection title="Enterprise SSO">
        <DocSubSection title="How SSO Works">
          <DocParagraph>
            Enterprise SSO allows your organization to use your existing identity provider (IdP) to
            authenticate users into Moneat. Users sign in through your company's SSO portal and are
            automatically provisioned in Moneat.
          </DocParagraph>
          <Callout variant="info" title="Available on Team and Business plans">
            SSO is available on the Team and Business plan tiers. Contact support if you need help
            configuring SSO for your organization.
          </Callout>
        </DocSubSection>

        <DocSubSection title="SSO Login Flow">
          <StepList
            steps={[
              {
                title: 'Enter your email',
                content: 'On the login page, click "Sign in with SSO" and enter your work email address.',
              },
              {
                title: 'Redirect to your IdP',
                content: 'Moneat looks up your organization\'s SSO configuration and redirects you to your identity provider.',
              },
              {
                title: 'Authenticate',
                content: 'Sign in with your company credentials through your IdP.',
              },
              {
                title: 'Return to Moneat',
                content: 'After successful authentication, you\'re redirected back to Moneat and logged in automatically.',
              },
            ]}
          />
        </DocSubSection>
      </DocSection>

      <DocSection title="Team Invitations">
        <DocSubSection title="Inviting Members">
          <DocParagraph>
            Invite team members from <strong className="text-foreground">Settings → Organization → Members</strong>.
            You can invite individually or in bulk using email addresses.
          </DocParagraph>
          <ul className="list-disc list-inside space-y-1 text-sm text-muted-foreground">
            <li>Enter the email address(es) of the people you want to invite</li>
            <li>Select their role (Member or Admin)</li>
            <li>They'll receive an email invitation with a link to join your organization</li>
          </ul>
        </DocSubSection>

        <DocSubSection title="Managing Members">
          <DocParagraph>
            Organization admins can manage team members from the Members settings:
          </DocParagraph>
          <ul className="list-disc list-inside space-y-1 text-sm text-muted-foreground">
            <li><strong className="text-foreground">Change roles</strong> — Promote or demote members between Member and Admin roles</li>
            <li><strong className="text-foreground">Remove members</strong> — Remove a team member from your organization</li>
            <li><strong className="text-foreground">Resend invitations</strong> — Resend pending invitation emails</li>
            <li><strong className="text-foreground">Revoke invitations</strong> — Cancel pending invitations</li>
          </ul>
        </DocSubSection>
      </DocSection>

      <DocSection title="Email Verification">
        <DocParagraph>
          When signing up with email and password, users must verify their email address before
          accessing the dashboard. A verification link is sent to the provided email. If it doesn't
          arrive, you can request a new verification email from the verification page.
        </DocParagraph>
      </DocSection>
    </DocPage>
  )
}
