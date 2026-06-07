import {AlertCircle, Info, Lightbulb, AlertTriangle} from 'lucide-react'
import type {ReactNode} from 'react'

type AdmonitionType = 'info' | 'tip' | 'warning' | 'caution' | 'note' | 'danger'

interface AdmonitionStyle {
  icon: typeof Info
  border: string
  bg: string
  text: string
  body: string
  iconColor: string
}

const STYLES: Record<AdmonitionType, AdmonitionStyle> = {
  info: {
    icon: Info,
    border: 'border-sky-400/35',
    bg: 'bg-sky-950/35',
    text: 'text-sky-100',
    body: 'text-slate-200',
    iconColor: 'text-sky-300',
  },
  tip: {
    icon: Lightbulb,
    border: 'border-emerald-400/35',
    bg: 'bg-emerald-950/30',
    text: 'text-emerald-100',
    body: 'text-slate-200',
    iconColor: 'text-emerald-300',
  },
  warning: {
    icon: AlertTriangle,
    border: 'border-amber-400/35',
    bg: 'bg-amber-950/30',
    text: 'text-amber-100',
    body: 'text-slate-200',
    iconColor: 'text-amber-300',
  },
  caution: {
    icon: AlertCircle,
    border: 'border-red-400/35',
    bg: 'bg-red-950/30',
    text: 'text-red-100',
    body: 'text-slate-200',
    iconColor: 'text-red-300',
  },
  note: {
    icon: Info,
    border: 'border-slate-500/45',
    bg: 'bg-slate-900/55',
    text: 'text-slate-100',
    body: 'text-slate-200',
    iconColor: 'text-slate-300',
  },
  danger: {
    icon: AlertCircle,
    border: 'border-red-400/35',
    bg: 'bg-red-950/30',
    text: 'text-red-100',
    body: 'text-slate-200',
    iconColor: 'text-red-300',
  },
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
    <div role={role} className={`my-6 rounded-md border ${style.border} ${style.bg} p-5`}>
      <div className={`mb-1 flex items-center gap-2 font-semibold ${style.text}`}>
        <Icon className={`size-4 ${style.iconColor}`} />
        {title ?? type.charAt(0).toUpperCase() + type.slice(1)}
      </div>
      <div className={`text-sm leading-6 ${style.body} [&_p]:m-0 [&_p]:text-current`}>{children}</div>
    </div>
  )
}
