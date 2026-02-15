import {Globe, Lock} from 'lucide-react'
import {cn} from '@/lib/utils'

interface BrowserWindowContainerProps {
  children: React.ReactNode
  url?: string
  browserName?: string
  osName?: string
  className?: string
}

export function BrowserWindowContainer({
  children,
  url,
  className,
}: BrowserWindowContainerProps) {
  const displayUrl = url || 'about:blank'

  // Truncate very long URLs for the address bar display
  const truncatedUrl =
    displayUrl.length > 80 ? displayUrl.slice(0, 77) + '...' : displayUrl

  return (
    <div className={cn('rounded-xl border bg-card shadow-sm overflow-hidden', className)}>
      {/* Browser chrome / title bar */}
      <div className="flex items-center gap-2 px-3 py-2 border-b bg-muted/50">
        {/* Traffic light dots */}
        <div className="flex gap-1.5 shrink-0">
          <div className="w-3 h-3 rounded-full bg-[#ff5f57]/80 border border-[#e0443e]/40" />
          <div className="w-3 h-3 rounded-full bg-[#febc2e]/80 border border-[#d4a528]/40" />
          <div className="w-3 h-3 rounded-full bg-[#28c840]/80 border border-[#1aab29]/40" />
        </div>

        {/* Address bar */}
        <div className="flex-1 flex items-center gap-1.5 mx-2 px-3 py-1 rounded-md bg-background border text-xs text-muted-foreground min-w-0">
          <Lock className="h-3 w-3 shrink-0 text-green-600 dark:text-green-400" />
          <Globe className="h-3 w-3 shrink-0 opacity-50" />
          <span className="truncate select-all" title={url}>
            {truncatedUrl}
          </span>
        </div>
      </div>

      {/* Content area */}
      <div className="relative bg-black overflow-hidden">
        {children}
      </div>
    </div>
  )
}
