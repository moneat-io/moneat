import {useState} from 'react'
import * as Sentry from '@sentry/react'

type FeedbackState = 'idle' | 'thumbs-up' | 'thumbs-down' | 'submitted'

export function DocsFeedback({slug}: {slug: string}) {
  const [state, setState] = useState<FeedbackState>('idle')
  const [comment, setComment] = useState('')

  const handleSubmitFeedback = () => {
    Sentry.captureFeedback({
      message: comment || 'Thumbs down (no comment)',
      tags: {slug, type: 'docs-feedback', rating: 'negative'},
      url: window.location.origin + window.location.pathname,
    })
    setState('submitted')
  }

  if (state === 'thumbs-up' || state === 'submitted') {
    return (
      <div className="mt-16 border-t border-white/10 pt-7">
        <p className="text-[13.5px] text-slate-400">
          {state === 'thumbs-up' ? 'Glad it was helpful.' : 'Thanks — your feedback was recorded.'}
        </p>
      </div>
    )
  }

  return (
    <div className="mt-16 border-t border-white/10 pt-7">
      <div className="flex flex-wrap items-center gap-3">
        <span className="text-[13.5px] text-slate-400">Was this page helpful?</span>
        <button
          type="button"
          onClick={() => setState('thumbs-up')}
          className="rounded-lg border border-white/[0.1] bg-white/[0.02] px-3 py-1.5 text-[13px] text-slate-300 transition-colors hover:border-emerald-400/40 hover:text-emerald-200"
        >
          Yes
        </button>
        <button
          type="button"
          onClick={() => setState('thumbs-down')}
          aria-expanded={state === 'thumbs-down'}
          className="rounded-lg border border-white/[0.1] bg-white/[0.02] px-3 py-1.5 text-[13px] text-slate-300 transition-colors hover:border-rose-400/40 hover:text-rose-200"
        >
          No
        </button>
      </div>

      {state === 'thumbs-down' && (
        <div className="mt-4 max-w-md">
          <label htmlFor="docs-feedback-comment" className="sr-only">
            What could be improved?
          </label>
          <textarea
            id="docs-feedback-comment"
            value={comment}
            onChange={(e) => setComment(e.target.value)}
            placeholder="What could be improved?"
            rows={3}
            className="w-full resize-none rounded-lg border border-white/[0.1] bg-white/[0.02] px-3 py-2 text-[13px] text-slate-100 placeholder:text-slate-500 focus:border-indigo-400/50 focus:outline-none"
          />
          <div className="mt-2 flex justify-end">
            <button
              type="button"
              onClick={handleSubmitFeedback}
              className="rounded-lg bg-indigo-600 px-3.5 py-1.5 text-[13px] font-medium text-white transition-colors hover:bg-indigo-500"
            >
              Submit feedback
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
