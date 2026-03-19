import {datadogRum} from '@datadog/browser-rum'
import {datadogLogs} from '@datadog/browser-logs'

export interface DatadogInitOptions {
  applicationId: string
  clientToken: string
  proxyUrl?: string
  backendUrl?: string
  service?: string
  env?: string
}

export function resolveProxyUrl(proxyUrl?: string, backendUrl?: string): string {
  const base = proxyUrl || (backendUrl || 'https://api.moneat.io')
  return base.replace(/\/+$/, '') + '/dd'
}

function joinUrl(base: string, path: string): string {
  return base.replace(/\/+$/, '') + '/' + path.replace(/^\/+/, '')
}

function isConfigured(value: string | undefined): value is string {
  if (!value || !value.trim()) return false
  if (value.startsWith('__') && value.endsWith('__')) return false
  return true
}

export function initDatadog(options: DatadogInitOptions): void {
  if (!isConfigured(options.applicationId) || !isConfigured(options.clientToken)) {
    return
  }

  const ddProxyUrl = resolveProxyUrl(options.proxyUrl, options.backendUrl)
  const service = options.service || 'moneat-dashboard'
  const env = options.env || 'production'
  const proxyFn = ({path, parameters}: {path: string; parameters: string}) =>
    joinUrl(ddProxyUrl, path) + (parameters ? `?${parameters}` : '')

  datadogRum.init({
    applicationId: options.applicationId,
    clientToken: options.clientToken,
    site: 'datadoghq.com',
    proxy: proxyFn,
    service,
    env,
    sessionSampleRate: 100,
    sessionReplaySampleRate: 100,
    trackUserInteractions: true,
    trackResources: true,
    trackLongTasks: true,
    defaultPrivacyLevel: 'mask-user-input',
  })

  datadogLogs.init({
    clientToken: options.clientToken,
    site: 'datadoghq.com',
    proxy: proxyFn,
    service,
    env,
    forwardErrorsToLogs: true,
    sessionSampleRate: 100,
  })
}
