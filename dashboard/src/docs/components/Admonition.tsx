import {AlertCircle, Info, Lightbulb, AlertTriangle} from 'lucide-react'
import type {ReactNode} from 'react'

type AdmonitionType = 'info' | 'tip' | 'warning' | 'caution' | 'note' | 'danger'

const STYLES: Record<AdmonitionType, {icon: typeof Info; border: string; bg: string; text: string; iconColor: string}> = {
  info: {icon: Info, border: 'border-sky-300', bg: 'bg-sky-50', text: 'text-sky-900', iconColor: 'text-sky-700'},
  tip: {icon: Lightbulb, border: 'border-emerald-300', bg: 'bg-emerald-50', text: 'text-emerald-900', iconColor: 'text-emerald-700'},
  warning: {icon: AlertTriangle, border: 'border-amber-300', bg: 'bg-amber-50', text: 'text-amber-900', iconColor: 'text-amber-700'},
  caution: {icon: AlertCircle, border: 'border-red-300', bg: 'bg-red-50', text: 'text-red-900', iconColor: 'text-red-700'},
  note: {icon: Info, border: 'border-slate-300', bg: 'bg-slate-50', text: 'text-slate-900', iconColor: 'text-slate-600'},
  danger: {icon: AlertCircle, border: 'border-red-300', bg: 'bg-red-50', text: 'text-red-900', iconColor: 'text-red-700'},
}

const alertTypes: AdmonitionType[] = ['warning', 'caution', 'danger']

interface AdmonitionProps {
  type?: AdmonitionType
  title?: string
  children: ReactNode
}

export default function Admonition({type = 'info', title, children}: AdmonitionProps) {
  const style = STYLES[type] ?? STYLES.info
  const Icon = style.icon
  const role = alertTypes.includes(type) ? 'alert' : 'note'

  return (
    <div role={role} className={`my-4 rounded-lg border-l-4 ${style.border} ${style.bg} p-4`}>
      <div className={`mb-1 flex items-center gap-2 font-semibold ${style.text}`}>
        <Icon className={`size-4 ${style.iconColor}`} />
        {title ?? type.charAt(0).toUpperCase() + type.slice(1)}
      </div>
      <div className="text-sm text-slate-700">{children}</div>
    </div>
  )
}
