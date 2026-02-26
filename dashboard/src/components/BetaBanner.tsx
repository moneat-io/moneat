import {useState} from 'react'
import {X} from 'lucide-react'

interface BetaBannerProps {
  pageKey: string
}

export function BetaBanner({pageKey}: BetaBannerProps) {
  const storageKey = `beta-banner-dismissed-${pageKey}`
  const [dismissed, setDismissed] = useState(() => localStorage.getItem(storageKey) === 'true')

  if (dismissed) return null

  function dismiss() {
    localStorage.setItem(storageKey, 'true')
    setDismissed(true)
  }

  return (
    <div className="flex items-center gap-3 px-4 py-2.5 bg-blue-600 text-white text-sm">
      <span className="flex-1">
        🧪 This feature is currently in <strong>beta</strong>. You may encounter bugs or incomplete functionality.
        {' '}If you run into any issues, please reach out to{' '}
        <a href="mailto:support@moneat.io" className="underline font-medium hover:text-blue-100">
          support@moneat.io
        </a>
        .
      </span>
      <button
        onClick={dismiss}
        aria-label="Dismiss beta banner"
        className="flex-shrink-0 rounded p-0.5 hover:bg-blue-500 transition-colors"
      >
        <X className="h-4 w-4" />
      </button>
    </div>
  )
}
