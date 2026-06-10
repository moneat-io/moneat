export type ServiceScopeId = string

export interface ServiceScopeParams {
  services?: readonly string[]
  serviceIds?: readonly ServiceScopeId[]
}

export function appendServiceScopeParams(qs: URLSearchParams, params?: ServiceScopeParams) {
  params?.services?.forEach((service) => qs.append('services', service))
  params?.serviceIds?.forEach((serviceId) => qs.append('serviceIds', String(serviceId)))
}

export function serviceScopeQuery(params?: ServiceScopeParams): string {
  const qs = new URLSearchParams()
  appendServiceScopeParams(qs, params)
  return qs.toString()
}
