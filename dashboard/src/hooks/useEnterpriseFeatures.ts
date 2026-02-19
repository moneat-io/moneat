import { useQuery } from '@tanstack/react-query'

interface FeaturesResponse {
  enterprise: boolean
  modules: string[]
}

const API_BASE = import.meta.env.VITE_BACKEND_URL || import.meta.env.VITE_API_URL || ''
const FEATURES_URL = API_BASE ? `${API_BASE.replace(/\/$/, '')}/features` : '/features'

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
      if (!res.ok) return { enterprise: false, modules: [] }
      return res.json()
    },
    staleTime: 5 * 60 * 1000, // Cache for 5 minutes
    retry: false,
  })
}

export function useHasModule(moduleName: string): boolean {
  const { data } = useEnterpriseFeatures()
  return hasEnterpriseModule(data, moduleName)
}
