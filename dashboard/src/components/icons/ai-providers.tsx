// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

import { cn } from '@/lib/utils'

interface ProviderIconProps {
  className?: string
}

export function OpenAIIcon({ className }: ProviderIconProps) {
  return (
    <svg viewBox="0 0 24 24" fill="currentColor" className={cn('h-4 w-4', className)}>
      <path d="M22.282 9.821a5.985 5.985 0 0 0-.516-4.91 6.046 6.046 0 0 0-6.51-2.9A6.065 6.065 0 0 0 4.981 4.18a5.985 5.985 0 0 0-3.998 2.9 6.046 6.046 0 0 0 .743 7.097 5.98 5.98 0 0 0 .51 4.911 6.051 6.051 0 0 0 6.515 2.9A5.985 5.985 0 0 0 13.26 24a6.056 6.056 0 0 0 5.772-4.206 5.99 5.99 0 0 0 3.997-2.9 6.056 6.056 0 0 0-.747-7.073zM13.26 22.43a4.476 4.476 0 0 1-2.876-1.04l.141-.081 4.779-2.758a.795.795 0 0 0 .392-.681v-6.737l2.02 1.168a.071.071 0 0 1 .038.052v5.583a4.504 4.504 0 0 1-4.494 4.494zM3.6 18.304a4.47 4.47 0 0 1-.535-3.014l.142.085 4.783 2.759a.771.771 0 0 0 .78 0l5.843-3.369v2.332a.08.08 0 0 1-.033.062L9.74 19.95a4.5 4.5 0 0 1-6.14-1.646zM2.34 7.896a4.485 4.485 0 0 1 2.366-1.973V11.6a.766.766 0 0 0 .388.676l5.815 3.355-2.02 1.168a.076.076 0 0 1-.071 0l-4.83-2.786A4.504 4.504 0 0 1 2.34 7.872zm16.597 3.855l-5.833-3.387L15.119 7.2a.076.076 0 0 1 .071 0l4.83 2.791a4.494 4.494 0 0 1-.676 8.105v-5.678a.79.79 0 0 0-.407-.667zm2.01-3.023l-.141-.085-4.774-2.782a.776.776 0 0 0-.785 0L9.409 9.23V6.897a.066.066 0 0 1 .028-.061l4.83-2.787a4.5 4.5 0 0 1 6.68 4.66zm-12.64 4.135l-2.02-1.164a.08.08 0 0 1-.038-.057V6.075a4.5 4.5 0 0 1 7.375-3.453l-.142.08L8.704 5.46a.795.795 0 0 0-.393.681zm1.097-2.365l2.602-1.5 2.607 1.5v2.999l-2.597 1.5-2.607-1.5z" />
    </svg>
  )
}

export function AnthropicIcon({ className }: ProviderIconProps) {
  return (
    <svg viewBox="0 0 24 24" fill="currentColor" className={cn('h-4 w-4', className)}>
      <path d="M17.304 3.541h-3.48l6.157 16.918h3.48zm-10.609 0L.539 20.459H4.16l1.262-3.467h6.478l1.262 3.467h3.622L10.627 3.541zm.615 10.88L9.346 8.29l2.036 6.131z" />
    </svg>
  )
}

export function GoogleIcon({ className }: ProviderIconProps) {
  return (
    <svg viewBox="0 0 24 24" className={cn('h-4 w-4', className)}>
      <path d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92a5.06 5.06 0 0 1-2.2 3.32v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.1z" fill="#4285F4" />
      <path d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z" fill="#34A853" />
      <path d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z" fill="#FBBC05" />
      <path d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z" fill="#EA4335" />
    </svg>
  )
}

export function MistralIcon({ className }: ProviderIconProps) {
  return (
    <svg viewBox="0 0 24 24" fill="currentColor" className={cn('h-4 w-4', className)}>
      <path d="M3 3h4v4H3zm14 0h4v4h-4zM3 7h4v4H3zm7 0h4v4h-4zm7 0h4v4h-4zM3 11h4v4H3zm3.5 0H11v4H6.5zm7 0H18v4h-4.5zm3.5 0h4v4h-4zM3 15h4v4H3zm7 0h4v4h-4zm7 0h4v4h-4zM3 19h4v4H3zm14 0h4v4h-4z" />
    </svg>
  )
}

export function CohereIcon({ className }: ProviderIconProps) {
  return (
    <svg viewBox="0 0 24 24" fill="currentColor" className={cn('h-4 w-4', className)}>
      <circle cx="12" cy="12" r="10" fill="none" stroke="currentColor" strokeWidth="2" />
      <path d="M8 12a4 4 0 1 1 8 0" strokeWidth="2" stroke="currentColor" fill="none" />
      <circle cx="12" cy="12" r="1.5" />
    </svg>
  )
}

function DefaultProviderIcon({ className }: ProviderIconProps) {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={cn('h-4 w-4', className)}>
      <path d="M12 2a4 4 0 0 1 4 4v1a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V6a4 4 0 0 1 4-4z" />
      <rect x="3" y="10" width="18" height="10" rx="2" />
      <circle cx="8.5" cy="15" r="1" fill="currentColor" />
      <circle cx="15.5" cy="15" r="1" fill="currentColor" />
    </svg>
  )
}

const PROVIDER_COLORS: Record<string, string> = {
  openai: 'text-[#10A37F]',
  anthropic: 'text-[#D97757]',
  google: '',
  mistral: 'text-[#FF7000]',
  cohere: 'text-[#39594D]',
}

const PROVIDER_ICONS: Record<string, React.ComponentType<ProviderIconProps>> = {
  openai: OpenAIIcon,
  anthropic: AnthropicIcon,
  google: GoogleIcon,
  mistral: MistralIcon,
  cohere: CohereIcon,
}

interface ProviderLogoProps {
  provider: string
  className?: string
  showName?: boolean
}

export function ProviderLogo({ provider, className, showName = true }: ProviderLogoProps) {
  const normalized = provider.toLowerCase().trim()
  const IconComponent = PROVIDER_ICONS[normalized] ?? DefaultProviderIcon
  const colorClass = PROVIDER_COLORS[normalized] ?? 'text-muted-foreground'

  return (
    <span className={cn('inline-flex items-center gap-1.5', className)}>
      <IconComponent className={cn('h-3.5 w-3.5 shrink-0', colorClass)} />
      {showName && (
        <span className="text-xs text-muted-foreground">{provider}</span>
      )}
    </span>
  )
}
