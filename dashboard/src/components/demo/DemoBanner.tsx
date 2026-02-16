import {isDemo} from '@/lib/demo'
import {Info} from 'lucide-react'

export function DemoBanner() {
  if (!isDemo()) {
    return null
  }

  return (
    <div className="bg-amber-500/10 border-b border-amber-500/20 px-4 py-2">
      <div className="flex items-center justify-center gap-2 text-sm text-amber-700 dark:text-amber-300">
        <Info className="h-4 w-4" />
        <span className="font-medium">Demo Mode</span>
        <span className="text-muted-foreground">•</span>
        <span>You're viewing read-only demo data. All write operations are disabled.</span>
      </div>
    </div>
  )
}
