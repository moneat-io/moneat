import { cn } from '@/lib/utils'

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
        viewBox="0 0 64 48"
        className={cn('h-8', className)}
        aria-label="Moneat"
      >
        <polyline
          points="2,30 16,30 22,12 30,36 38,12 44,30 62,30"
          fill="none"
          stroke="#38bdf8"
          strokeWidth="3.5"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      </svg>
    )
  }

  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 240 48"
      className={cn('h-8', className)}
      aria-label="Moneat"
    >
      <polyline
        points="0,30 16,30 22,12 30,36 38,12 44,30 60,30"
        fill="none"
        stroke="#38bdf8"
        strokeWidth="3.5"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <text
        x="68"
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
