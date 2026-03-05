import {AlertCircle, Info, Lightbulb, AlertTriangle} from 'lucide-react'
import type {ReactNode} from 'react'

const STYLES: Record<string, {icon: typeof Info; border: string; bg: string; text: string; iconColor: string}> = {
  info: {icon: Info, border: 'border-sky-500/40', bg: 'bg-sky-500/10', text: 'text-sky-300', iconColor: 'text-sky-400'},
  tip: {icon: Lightbulb, border: 'border-emerald-500/40', bg: 'bg-emerald-500/10', text: 'text-emerald-300', iconColor: 'text-emerald-400'},
  warning: {icon: AlertTriangle, border: 'border-amber-500/40', bg: 'bg-amber-500/10', text: 'text-amber-300', iconColor: 'text-amber-400'},
  caution: {icon: AlertCircle, border: 'border-red-500/40', bg: 'bg-red-500/10', text: 'text-red-300', iconColor: 'text-red-400'},
  note: {icon: Info, border: 'border-slate-500/40', bg: 'bg-slate-500/10', text: 'text-slate-300', iconColor: 'text-slate-400'},
  danger: {icon: AlertCircle, border: 'border-red-500/40', bg: 'bg-red-500/10', text: 'text-red-300', iconColor: 'text-red-400'},
}

interface AdmonitionProps {
  type?: string
  title?: string
  children: ReactNode
}

export default function Admonition({type = 'info', title, children}: AdmonitionProps) {
  const style = STYLES[type] ?? STYLES.info
  const Icon = style.icon

  return (
    <div className={`my-4 rounded-lg border-l-4 ${style.border} ${style.bg} p-4`}>
      <div className={`flex items-center gap-2 font-semibold ${style.text} mb-1`}>
        <Icon className={`h-4 w-4 ${style.iconColor}`} />
        {title ?? type.charAt(0).toUpperCase() + type.slice(1)}
      </div>
      <div className="text-sm text-slate-300">{children}</div>
    </div>
  )
}
