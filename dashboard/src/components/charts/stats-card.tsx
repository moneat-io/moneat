import { Card, CardContent } from '@/components/ui/card'
import { LucideIcon } from 'lucide-react'

const ACCENT_STYLES: Record<string, { bar: string; icon: string }> = {
  blue: { bar: 'bg-blue-500', icon: 'bg-blue-500/15 text-blue-600 dark:text-blue-400' },
  amber: { bar: 'bg-amber-500', icon: 'bg-amber-500/15 text-amber-600 dark:text-amber-400' },
  emerald: { bar: 'bg-emerald-500', icon: 'bg-emerald-500/15 text-emerald-600 dark:text-emerald-400' },
  violet: { bar: 'bg-violet-500', icon: 'bg-violet-500/15 text-violet-600 dark:text-violet-400' },
}

interface StatsCardProps {
  title: string
  value: string | number
  icon: LucideIcon
  trend?: {
    value: number
    positive: boolean
  }
  /** Preset accent: blue, amber, emerald, violet. Adds a top bar and colored icon. */
  accent?: keyof typeof ACCENT_STYLES
  className?: string
}

export function StatsCard({ title, value, icon: Icon, trend, accent, className }: StatsCardProps) {
  const styles = accent ? ACCENT_STYLES[accent] : null
  return (
    <Card className={`overflow-hidden ${className}`}>
      {styles && <div className={`h-1 w-full shrink-0 ${styles.bar}`} aria-hidden />}
      <CardContent className="p-6">
        <div className="flex items-center justify-between">
          <div className="flex-1">
            <p className="text-sm font-medium text-muted-foreground">{title}</p>
            <p className="text-2xl font-bold mt-2">{value}</p>
            {trend && (
              <p className={`text-xs mt-1 ${trend.positive ? 'text-green-600' : 'text-red-600'}`}>
                {trend.positive ? '↑' : '↓'} {Math.abs(trend.value)}% from last period
              </p>
            )}
          </div>
          <div className="ml-4">
            <div
              className={`h-12 w-12 rounded-xl flex items-center justify-center ${
                styles ? styles.icon : 'bg-primary/10 text-primary'
              }`}
            >
              <Icon className="h-6 w-6" />
            </div>
          </div>
        </div>
      </CardContent>
    </Card>
  )
}
