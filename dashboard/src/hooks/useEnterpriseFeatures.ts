import { useQuery } from '@tanstack/react-query'

interface FeaturesResponse {
  enterprise: boolean
  modules: string[]
}

const API_BASE = import.meta.env.VITE_API_URL || ''

export function useEnterpriseFeatures() {
  return useQuery<FeaturesResponse>({
    queryKey: ['features'],
    queryFn: async () => {
      const res = await fetch(`${API_BASE}/features`)
      if (!res.ok) return { enterprise: false, modules: [] }
      return res.json()
    },
    staleTime: 5 * 60 * 1000, // Cache for 5 minutes
    retry: false,
  })
}

export function useHasModule(moduleName: string): boolean {
  const { data } = useEnterpriseFeatures()
  return data?.modules?.includes(moduleName) ?? false
}
