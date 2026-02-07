import {cn} from '@/lib/utils'

interface LogoProps {
  className?: string
  /** Show only the pulse mark without the wordmark */
  markOnly?: boolean
}

export function Logo({ className, markOnly = false }: LogoProps) {
  if (markOnly) {
    return (
      <svg
        xmlns="http://www.w3.org/2000/svg"
        viewBox="0 0 48 48"
        className={cn('h-8', className)}
        aria-label="Moneat"
      >
        <circle
          cx="24"
          cy="24"
          r="18"
          fill="none"
          stroke="#38bdf8"
          strokeWidth="2.5"
        />
        <polyline
          points="10,24 14,24 18,15 24,31 30,15 34,24 38,24"
          fill="none"
          stroke="#38bdf8"
          strokeWidth="2.8"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      </svg>
    )
  }

  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 236 48"
      className={cn('h-8', className)}
      aria-label="Moneat"
    >
      <circle cx="24" cy="24" r="18" fill="none" stroke="#38bdf8" strokeWidth="2.5" />
      <polyline
        points="10,24 14,24 18,15 24,31 30,15 34,24 38,24"
        fill="none"
        stroke="#38bdf8"
        strokeWidth="2.8"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <text
        x="52"
        y="31"
        fontFamily="system-ui, -apple-system, sans-serif"
        fontSize="28"
        fontWeight="600"
        fill="currentColor"
        letterSpacing="-0.5"
      >
        moneat
      </text>
    </svg>
  )
}
