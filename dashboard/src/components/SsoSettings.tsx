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

import { useState, useEffect, useRef } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Switch } from '@/components/ui/switch'
import { Badge } from '@/components/ui/badge'
import { useToast } from '@/hooks/useToast'
import { AlertCircle, Check, Loader2, Shield, ShieldCheck } from 'lucide-react'

export function SsoTab({
  organizationId,
  hasSamlModule = false,
  canConfigure = true,
}: Readonly<{ organizationId?: number; hasSamlModule?: boolean; canConfigure?: boolean }>) {
  const hasOrganization = organizationId !== undefined
  const readOnly = !canConfigure || !hasOrganization
  const queryClient = useQueryClient()
  const { toast } = useToast()
  const [providerType, setProviderType] = useState<'saml' | 'oidc'>('oidc')

  const requireOrganizationId = () => {
    if (organizationId === undefined) throw new Error('No organization found')
    return organizationId
  }

  const { data: ssoConfig, isLoading: configLoading } = useQuery({
    queryKey: ['ssoConfig', organizationId],
    queryFn: () => api.getSsoConfig(requireOrganizationId()),
    enabled: hasOrganization,
    retry: false,
  })

  const [formData, setFormData] = useState({
    isEnabled: true,
    // SAML fields
    idpEntityId: '',
    idpSsoUrl: '',
    idpCertificate: '',
    // OIDC fields
    oidcIssuerUrl: '',
    oidcClientId: '',
    oidcClientSecret: '',
    // Shared
    emailDomain: '',
    requireSso: false,
  })

  // Initialize form when ssoConfig loads - using ref to track initialization
  const initializedRef = useRef(false)
  useEffect(() => {
    if (ssoConfig && !initializedRef.current) {
      initializedRef.current = true
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setProviderType(ssoConfig.providerType === 'saml' ? 'saml' : 'oidc')
      setFormData({
        isEnabled: ssoConfig.isEnabled,
        idpEntityId: ssoConfig.idpEntityId || '',
        idpSsoUrl: ssoConfig.idpSsoUrl || '',
        idpCertificate: ssoConfig.idpCertificate || '',
        oidcIssuerUrl: ssoConfig.oidcIssuerUrl || '',
        oidcClientId: ssoConfig.oidcClientId || '',
        oidcClientSecret: '',
        emailDomain: ssoConfig.emailDomain || '',
        requireSso: ssoConfig.requireSso || false,
      })
    }
  }, [ssoConfig])

  const saveMutation = useMutation({
    mutationFn: async () => {
      const orgId = requireOrganizationId()

      return api.configureSso(orgId, {
        providerType,
        ...formData,
      })
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['ssoConfig'] })
      toast({
        title: 'SSO configuration saved',
        description: 'Your SSO settings have been updated successfully.',
      })
    },
    onError: (error: Error) => {
      toast({
        title: 'Failed to save SSO configuration',
        description: error.message || 'An error occurred. Please try again.',
        variant: 'destructive',
      })
    },
  })

  const deleteMutation = useMutation({
    mutationFn: async () => {
      return api.deleteSsoConfig(requireOrganizationId())
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['ssoConfig'] })
      toast({
        title: 'SSO configuration deleted',
        description: 'SSO has been disabled for your organization.',
      })
      // Reset form
      setFormData({
        isEnabled: true,
        idpEntityId: '',
        idpSsoUrl: '',
        idpCertificate: '',
        oidcIssuerUrl: '',
        oidcClientId: '',
        oidcClientSecret: '',
        emailDomain: '',
        requireSso: false,
      })
    },
    onError: (error: Error) => {
      toast({
        title: 'Failed to delete SSO configuration',
        description: error.message || 'An error occurred. Please try again.',
        variant: 'destructive',
      })
    },
  })

  const verifyDomainMutation = useMutation({
    mutationFn: async () => {
      return api.verifySsoDomain(requireOrganizationId())
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['ssoConfig'] })
      toast({
        title: 'SSO domain verified',
        description: 'Your SSO email domain is ready to use.',
      })
    },
    onError: (error: Error) => {
      toast({
        title: 'Failed to verify SSO domain',
        description: error.message || 'Add the TXT record and try again.',
        variant: 'destructive',
      })
    },
  })

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (readOnly) return
    saveMutation.mutate()
  }

  if (configLoading) {
    return (
      <Card>
        <CardContent className="flex items-center justify-center py-8">
          <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
        </CardContent>
      </Card>
    )
  }

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader className="pb-3">
          <div className="flex items-center gap-2">
            <Shield className="h-4 w-4 text-primary" />
            <CardTitle className="text-base">Single Sign-On (SSO)</CardTitle>
          </div>
          <CardDescription className="text-xs">
            Configure SAML 2.0 or OIDC authentication for your organization.
          </CardDescription>
        </CardHeader>
        <CardContent className="pt-0">
          <form onSubmit={handleSubmit} className="space-y-4">
            <div className="space-y-3">
              <div className="space-y-1.5">
                <Label className="text-sm">Provider Type</Label>
                <Select
                  value={providerType}
                  onValueChange={(value: 'saml' | 'oidc') => setProviderType(value)}
                  disabled={readOnly}
                >
                  <SelectTrigger className="h-8">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="oidc">OIDC (OpenID Connect)</SelectItem>
                    <SelectItem value="saml" disabled={!hasSamlModule}>
                      SAML 2.0{!hasSamlModule && ' (Enterprise)'}
                    </SelectItem>
                  </SelectContent>
                </Select>
                <p className="text-xs text-muted-foreground">
                  {providerType === 'oidc'
                    ? 'Works with any OIDC provider including Authentik, Authelia, Keycloak, Okta, and Azure AD'
                    : 'For enterprise identity providers. Requires an enterprise license for self-hosted deployments.'}
                </p>
              </div>

              {providerType === 'saml' ? (
                <>
                  <div className="space-y-1.5">
                    <Label htmlFor="idpEntityId" className="text-sm">IdP Entity ID</Label>
                    <Input
                      id="idpEntityId"
                      className="h-8"
                      value={formData.idpEntityId}
                      onChange={(e) => setFormData({ ...formData, idpEntityId: e.target.value })}
                      placeholder="https://idp.example.com/metadata"
                      required={providerType === 'saml'}
                      disabled={readOnly}
                    />
                  </div>

                  <div className="space-y-1.5">
                    <Label htmlFor="idpSsoUrl" className="text-sm">IdP SSO URL</Label>
                    <Input
                      id="idpSsoUrl"
                      className="h-8"
                      value={formData.idpSsoUrl}
                      onChange={(e) => setFormData({ ...formData, idpSsoUrl: e.target.value })}
                      placeholder="https://idp.example.com/sso/saml"
                      required={providerType === 'saml'}
                      disabled={readOnly}
                    />
                  </div>

                  <div className="space-y-1.5">
                    <Label htmlFor="idpCertificate" className="text-sm">IdP X.509 Certificate</Label>
                    <Textarea
                      id="idpCertificate"
                      value={formData.idpCertificate}
                      onChange={(e) => setFormData({ ...formData, idpCertificate: e.target.value })}
                      placeholder="-----BEGIN CERTIFICATE-----&#10;MIIDXTCCAkWgAwIBAgIJAJC1HiIAZAiIMA0GCSqGSI...&#10;-----END CERTIFICATE-----"
                      rows={4}
                      className="font-mono text-xs min-h-0 py-2"
                      required={providerType === 'saml'}
                      disabled={readOnly}
                    />
                    <p className="text-xs text-muted-foreground">
                      Paste the X.509 certificate from your identity provider
                    </p>
                  </div>

                  {ssoConfig?.spEntityId && (
                    <div className="space-y-1.5">
                      <Label className="text-sm">SP Entity ID (read-only)</Label>
                      <Input value={ssoConfig.spEntityId} readOnly className="bg-muted h-8" />
                      <p className="text-xs text-muted-foreground">
                        Use this value when configuring Moneat in your IdP
                      </p>
                    </div>
                  )}
                </>
              ) : (
                <>
                  <div className="space-y-1.5">
                    <Label htmlFor="oidcIssuerUrl" className="text-sm">Issuer URL</Label>
                    <Input
                      id="oidcIssuerUrl"
                      className="h-8"
                      value={formData.oidcIssuerUrl}
                      onChange={(e) => setFormData({ ...formData, oidcIssuerUrl: e.target.value })}
                      placeholder="https://auth.example.com/application/o/app-slug/"
                      required={providerType === 'oidc'}
                      disabled={readOnly}
                    />
                    <p className="text-xs text-muted-foreground">
                      The issuer URL from your provider's openid-configuration document
                    </p>
                  </div>

                  <div className="space-y-1.5">
                    <Label htmlFor="oidcClientId" className="text-sm">Client ID</Label>
                    <Input
                      id="oidcClientId"
                      className="h-8"
                      value={formData.oidcClientId}
                      onChange={(e) => setFormData({ ...formData, oidcClientId: e.target.value })}
                      placeholder="0oa2abc3defGHI4jkl5m"
                      required={providerType === 'oidc'}
                      disabled={readOnly}
                    />
                  </div>

                  <div className="space-y-1.5">
                    <Label htmlFor="oidcClientSecret" className="text-sm">Client Secret</Label>
                    <Input
                      id="oidcClientSecret"
                      type="password"
                      className="h-8"
                      value={formData.oidcClientSecret}
                      onChange={(e) => setFormData({ ...formData, oidcClientSecret: e.target.value })}
                      placeholder={ssoConfig?.hasClientSecret ? '••••••••••••••••' : 'Enter client secret'}
                      required={providerType === 'oidc' && !ssoConfig?.hasClientSecret}
                      disabled={readOnly}
                    />
                    {ssoConfig?.hasClientSecret && (
                      <p className="text-xs text-muted-foreground">
                        Leave blank to keep existing secret
                      </p>
                    )}
                  </div>
                </>
              )}

              <div className="space-y-1.5">
                <Label htmlFor="emailDomain" className="text-sm">Email Domain</Label>
                <Input
                  id="emailDomain"
                  className="h-8"
                  value={formData.emailDomain}
                  onChange={(e) => setFormData({ ...formData, emailDomain: e.target.value })}
                  placeholder="company.com"
                  disabled={readOnly}
                />
                <p className="text-xs text-muted-foreground">
                  Users with this email domain will be prompted to use SSO (e.g., "company.com")
                </p>
              </div>

              {ssoConfig?.emailDomain && (
                <div className="rounded-lg border p-3">
                  <div className="flex items-center justify-between gap-3">
                    <div className="space-y-0.5">
                      <Label className="text-sm font-medium">Domain Verification</Label>
                      <div>
                        <Badge variant={ssoConfig.emailDomainVerified ? 'default' : 'secondary'}>
                          {ssoConfig.emailDomainVerified ? 'Verified' : 'Pending'}
                        </Badge>
                      </div>
                    </div>
                    <Button
                      type="button"
                      size="sm"
                      variant="outline"
                      onClick={() => verifyDomainMutation.mutate()}
                      disabled={readOnly || verifyDomainMutation.isPending || ssoConfig.emailDomainVerified}
                    >
                      {verifyDomainMutation.isPending ? (
                        <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                      ) : (
                        <ShieldCheck className="mr-2 h-4 w-4" />
                      )}
                      Verify
                    </Button>
                  </div>

                  {!ssoConfig.emailDomainVerified &&
                    ssoConfig.emailDomainVerificationRecordName &&
                    ssoConfig.emailDomainVerificationToken && (
                      <div className="mt-3 grid gap-2 md:grid-cols-2">
                        <div className="space-y-1.5">
                          <Label className="text-xs">TXT Name</Label>
                          <Input
                            readOnly
                            className="h-8 font-mono text-xs"
                            value={ssoConfig.emailDomainVerificationRecordName}
                          />
                        </div>
                        <div className="space-y-1.5">
                          <Label className="text-xs">TXT Value</Label>
                          <Input
                            readOnly
                            className="h-8 font-mono text-xs"
                            value={`moneat-sso=${ssoConfig.emailDomainVerificationToken}`}
                          />
                        </div>
                      </div>
                    )}
                </div>
              )}

              <div className="flex items-center justify-between rounded-lg border p-3">
                <div className="space-y-0.5">
                  <Label htmlFor="requireSso" className="text-sm font-medium">
                    Require SSO
                    {!hasSamlModule && (
                      <Badge variant="secondary" className="ml-2 text-[10px]">Enterprise</Badge>
                    )}
                  </Label>
                  <p className="text-xs text-muted-foreground">
                    Block password login for users in this organization
                  </p>
                </div>
                <Switch
                  id="requireSso"
                  checked={formData.requireSso}
                  onCheckedChange={(checked) => setFormData({ ...formData, requireSso: checked })}
                  disabled={readOnly || (!hasSamlModule && !formData.requireSso)}
                />
              </div>

              <div className="flex items-center justify-between rounded-lg border p-3">
                <div className="space-y-0.5">
                  <Label htmlFor="isEnabled" className="text-sm font-medium">
                    Enable SSO
                  </Label>
                  <p className="text-xs text-muted-foreground">
                    Allow users to log in via SSO
                  </p>
                </div>
                <Switch
                  id="isEnabled"
                  checked={formData.isEnabled}
                  onCheckedChange={(checked) => setFormData({ ...formData, isEnabled: checked })}
                  disabled={readOnly}
                />
              </div>
            </div>

            <div className="flex gap-2 pt-1">
              <Button type="submit" size="sm" disabled={readOnly || saveMutation.isPending}>
                {saveMutation.isPending ? (
                  <>
                    <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                    Saving...
                  </>
                ) : (
                  <>
                    <Check className="mr-2 h-4 w-4" />
                    Save Configuration
                  </>
                )}
              </Button>

              {ssoConfig && (
                <Button
                  type="button"
                  variant="destructive"
                  size="sm"
                  onClick={() => deleteMutation.mutate()}
                  disabled={readOnly || deleteMutation.isPending}
                >
                  {deleteMutation.isPending ? (
                    <>
                      <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                      Deleting...
                    </>
                  ) : (
                    'Delete SSO Configuration'
                  )}
                </Button>
              )}
            </div>
          </form>
        </CardContent>
      </Card>

      {formData.requireSso && (
        <Card className="border-warning-border bg-warning-bg">
          <CardContent className="py-3 px-4">
            <div className="flex gap-2">
              <AlertCircle className="h-4 w-4 text-warning-fg flex-shrink-0 mt-0.5" />
              <div className="space-y-0.5">
                <p className="text-sm font-medium">SSO Enforcement Enabled</p>
                <p className="text-xs text-muted-foreground">
                  When "Require SSO" is enabled, all users in your organization will be required to
                  log in via SSO. Password-based login will be blocked. Make sure SSO is working
                  correctly before enabling this option.
                </p>
              </div>
            </div>
          </CardContent>
        </Card>
      )}
    </div>
  )
}
