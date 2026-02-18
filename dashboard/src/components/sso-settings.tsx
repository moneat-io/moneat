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

import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'

/**
 * Stub SSO settings tab shown when the enterprise SSO module is not available.
 * The full implementation lives in enterprise/dashboard/src/components/sso-settings.tsx.
 */
export function SsoTab() {
  return (
    <Card>
      <CardHeader>
        <CardTitle>Single Sign-On (SSO)</CardTitle>
        <CardDescription>
          SSO authentication requires a Moneat Enterprise license.
        </CardDescription>
      </CardHeader>
      <CardContent>
        <p className="text-sm text-muted-foreground">
          Contact <a href="mailto:licensing@moneat.io" className="underline">licensing@moneat.io</a> to
          enable SAML, OIDC, and other enterprise authentication features.
        </p>
      </CardContent>
    </Card>
  )
}
