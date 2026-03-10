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
import { useToast } from '@/hooks/useToast'
import { AlertCircle, Check, Loader2, Shield } from 'lucide-react'

export function SsoTab() {
  const queryClient = useQueryClient()
  const { toast } = useToast()
  const [providerType, setProviderType] = useState<'saml' | 'oidc'>('oidc')

  // Uses default organization ID; update when multi-org support is added
  const orgId = 1

  const { data: ssoConfig, isLoading: configLoading } = useQuery({
    queryKey: ['ssoConfig', orgId],
    queryFn: () => api.getSsoConfig(orgId),
    enabled: !!orgId,
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
      if (!orgId) throw new Error('No organization found')
      
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
      if (!orgId) throw new Error('No organization found')
      return api.deleteSsoConfig(orgId)
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

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    saveMutation.mutate()
  }

  if (configLoading) {
    return (
      <Card>
        <CardContent className="flex items-center justify-center py-12">
          <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
        </CardContent>
      </Card>
    )
  }

  return (
    <div className="space-y-6">
      <Card>
        <CardHeader>
          <div className="flex items-center gap-2">
            <Shield className="h-5 w-5 text-primary" />
            <CardTitle>Single Sign-On (SSO)</CardTitle>
          </div>
          <CardDescription>
            Configure SAML 2.0 or OIDC authentication for your organization. SSO allows your team to
            log in using your company's identity provider.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <form onSubmit={handleSubmit} className="space-y-6">
            <div className="space-y-4">
              <div className="space-y-2">
                <Label>Provider Type</Label>
                <Select
                  value={providerType}
                  onValueChange={(value: 'saml' | 'oidc') => setProviderType(value)}
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="oidc">OIDC (OpenID Connect)</SelectItem>
                    <SelectItem value="saml">SAML 2.0</SelectItem>
                  </SelectContent>
                </Select>
                <p className="text-xs text-muted-foreground">
                  {providerType === 'oidc'
                    ? 'Recommended for modern identity providers like Okta, Auth0, Azure AD'
                    : 'For legacy enterprise identity providers'}
                </p>
              </div>

              {providerType === 'saml' ? (
                <>
                  <div className="space-y-2">
                    <Label htmlFor="idpEntityId">IdP Entity ID</Label>
                    <Input
                      id="idpEntityId"
                      value={formData.idpEntityId}
                      onChange={(e) => setFormData({ ...formData, idpEntityId: e.target.value })}
                      placeholder="https://idp.example.com/metadata"
                      required={providerType === 'saml'}
                    />
                  </div>

                  <div className="space-y-2">
                    <Label htmlFor="idpSsoUrl">IdP SSO URL</Label>
                    <Input
                      id="idpSsoUrl"
                      value={formData.idpSsoUrl}
                      onChange={(e) => setFormData({ ...formData, idpSsoUrl: e.target.value })}
                      placeholder="https://idp.example.com/sso/saml"
                      required={providerType === 'saml'}
                    />
                  </div>

                  <div className="space-y-2">
                    <Label htmlFor="idpCertificate">IdP X.509 Certificate</Label>
                    <Textarea
                      id="idpCertificate"
                      value={formData.idpCertificate}
                      onChange={(e) => setFormData({ ...formData, idpCertificate: e.target.value })}
                      placeholder="-----BEGIN CERTIFICATE-----&#10;MIIDXTCCAkWgAwIBAgIJAJC1HiIAZAiIMA0GCSqGSI...&#10;-----END CERTIFICATE-----"
                      rows={6}
                      className="font-mono text-xs"
                      required={providerType === 'saml'}
                    />
                    <p className="text-xs text-muted-foreground">
                      Paste the X.509 certificate from your identity provider
                    </p>
                  </div>

                  {ssoConfig?.spEntityId && (
                    <div className="space-y-2">
                      <Label>SP Entity ID (read-only)</Label>
                      <Input value={ssoConfig.spEntityId} readOnly className="bg-muted" />
                      <p className="text-xs text-muted-foreground">
                        Use this value when configuring Moneat in your IdP
                      </p>
                    </div>
                  )}
                </>
              ) : (
                <>
                  <div className="space-y-2">
                    <Label htmlFor="oidcIssuerUrl">Issuer URL</Label>
                    <Input
                      id="oidcIssuerUrl"
                      value={formData.oidcIssuerUrl}
                      onChange={(e) => setFormData({ ...formData, oidcIssuerUrl: e.target.value })}
                      placeholder="https://your-domain.okta.com"
                      required={providerType === 'oidc'}
                    />
                    <p className="text-xs text-muted-foreground">
                      The base URL of your OIDC provider
                    </p>
                  </div>

                  <div className="space-y-2">
                    <Label htmlFor="oidcClientId">Client ID</Label>
                    <Input
                      id="oidcClientId"
                      value={formData.oidcClientId}
                      onChange={(e) => setFormData({ ...formData, oidcClientId: e.target.value })}
                      placeholder="0oa2abc3defGHI4jkl5m"
                      required={providerType === 'oidc'}
                    />
                  </div>

                  <div className="space-y-2">
                    <Label htmlFor="oidcClientSecret">Client Secret</Label>
                    <Input
                      id="oidcClientSecret"
                      type="password"
                      value={formData.oidcClientSecret}
                      onChange={(e) => setFormData({ ...formData, oidcClientSecret: e.target.value })}
                      placeholder={ssoConfig?.hasClientSecret ? '••••••••••••••••' : 'Enter client secret'}
                      required={providerType === 'oidc' && !ssoConfig?.hasClientSecret}
                    />
                    {ssoConfig?.hasClientSecret && (
                      <p className="text-xs text-muted-foreground">
                        Leave blank to keep existing secret
                      </p>
                    )}
                  </div>
                </>
              )}

              <div className="space-y-2">
                <Label htmlFor="emailDomain">Email Domain</Label>
                <Input
                  id="emailDomain"
                  value={formData.emailDomain}
                  onChange={(e) => setFormData({ ...formData, emailDomain: e.target.value })}
                  placeholder="company.com"
                />
                <p className="text-xs text-muted-foreground">
                  Users with this email domain will be prompted to use SSO (e.g., "company.com")
                </p>
              </div>

              <div className="flex items-center justify-between rounded-lg border p-4">
                <div className="space-y-0.5">
                  <Label htmlFor="requireSso" className="text-base font-medium">
                    Require SSO
                  </Label>
                  <p className="text-sm text-muted-foreground">
                    Block password login for users in this organization
                  </p>
                </div>
                <Switch
                  id="requireSso"
                  checked={formData.requireSso}
                  onCheckedChange={(checked) => setFormData({ ...formData, requireSso: checked })}
                />
              </div>

              <div className="flex items-center justify-between rounded-lg border p-4">
                <div className="space-y-0.5">
                  <Label htmlFor="isEnabled" className="text-base font-medium">
                    Enable SSO
                  </Label>
                  <p className="text-sm text-muted-foreground">
                    Allow users to log in via SSO
                  </p>
                </div>
                <Switch
                  id="isEnabled"
                  checked={formData.isEnabled}
                  onCheckedChange={(checked) => setFormData({ ...formData, isEnabled: checked })}
                />
              </div>
            </div>

            <div className="flex gap-3">
              <Button type="submit" disabled={saveMutation.isPending}>
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
                  onClick={() => deleteMutation.mutate()}
                  disabled={deleteMutation.isPending}
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
        <Card className="border-yellow-500/50 bg-yellow-500/5">
          <CardContent className="pt-6">
            <div className="flex gap-3">
              <AlertCircle className="h-5 w-5 text-yellow-500 flex-shrink-0 mt-0.5" />
              <div className="space-y-1">
                <p className="text-sm font-medium">SSO Enforcement Enabled</p>
                <p className="text-sm text-muted-foreground">
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
