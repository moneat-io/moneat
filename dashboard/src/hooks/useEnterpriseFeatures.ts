import { useQuery } from '@tanstack/react-query'
import {API_BASE} from '@/lib/api/client'

interface FeaturesResponse {
  enterprise: boolean
  modules: string[]
  selfHost: boolean
}

const FEATURES_URL = `${API_BASE}/features`

function normalizeModuleName(value: string): string {
  return value.toLowerCase().replace(/[\s_-]/g, '')
}

export function hasEnterpriseModule(features: FeaturesResponse | undefined, moduleName: string): boolean {
  const target = normalizeModuleName(moduleName)
  return features?.modules?.some((module) => normalizeModuleName(module) === target) ?? false
}

export function useEnterpriseFeatures() {
  return useQuery<FeaturesResponse>({
    queryKey: ['features'],
    queryFn: async () => {
      const res = await fetch(FEATURES_URL)
      if (!res.ok) throw new Error('Unable to load feature availability')
      return res.json()
    },
    staleTime: 30 * 1000,
    refetchOnWindowFocus: true,
    retry: false,
  })
}

export function useHasModule(moduleName: string): boolean {
  const { data } = useEnterpriseFeatures()
  return hasEnterpriseModule(data, moduleName)
}

export function useIsSelfHosted(): boolean {
  const { data } = useEnterpriseFeatures()
  return data?.selfHost ?? false
}
