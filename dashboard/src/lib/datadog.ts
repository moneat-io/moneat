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
  if (proxyUrl) return proxyUrl
  return (backendUrl || 'https://api.moneat.io') + '/dd'
}

export function initDatadog(options: DatadogInitOptions): void {
  const ddProxyUrl = resolveProxyUrl(options.proxyUrl, options.backendUrl)
  const service = options.service || 'moneat-dashboard'
  const env = options.env || 'production'
  const proxyFn = ({path, parameters}: {path: string; parameters: string}) =>
    `${ddProxyUrl}${path}?${parameters}`

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
