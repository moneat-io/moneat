import {useState} from 'react'
import {ThumbsUp, ThumbsDown} from 'lucide-react'
import * as Sentry from '@sentry/react'

type FeedbackState = 'idle' | 'thumbs-up' | 'thumbs-down' | 'submitted'

export function BlogPostFeedback({slug}: {slug: string}) {
  const [state, setState] = useState<FeedbackState>('idle')
  const [comment, setComment] = useState('')

  const handleThumbsDown = () => setState('thumbs-down')

  const handleSubmitFeedback = () => {
    Sentry.captureFeedback({
      message: comment || 'Thumbs down (no comment)',
      tags: {slug, type: 'blog-feedback', rating: 'negative'},
      url: window.location.href,
    })
    setState('submitted')
  }

  if (state === 'thumbs-up' || state === 'submitted') {
    return (
      <div className="mt-12 pt-8 border-t border-slate-800 text-center">
        <p className="text-sm text-slate-400">
          {state === 'thumbs-up' ? 'Glad you found it helpful!' : 'Thanks for your feedback!'}
        </p>
      </div>
    )
  }

  return (
    <div className="mt-12 pt-8 border-t border-slate-800">
      <p className="text-sm text-slate-400 text-center mb-3">Was this article helpful?</p>

      <div className="flex justify-center gap-3 mb-4">
        <button
          onClick={() => setState('thumbs-up')}
          className="inline-flex items-center gap-1.5 px-3 py-1.5 text-sm rounded-lg border border-slate-700 text-slate-300 hover:border-sky-500 hover:text-sky-400 transition-colors"
        >
          <ThumbsUp className="h-4 w-4" /> Yes
        </button>
        <button
          onClick={handleThumbsDown}
          className="inline-flex items-center gap-1.5 px-3 py-1.5 text-sm rounded-lg border border-slate-700 text-slate-300 hover:border-sky-500 hover:text-sky-400 transition-colors"
          aria-expanded={state === 'thumbs-down'}
        >
          <ThumbsDown className="h-4 w-4" /> No
        </button>
      </div>

      {state === 'thumbs-down' && (
        <div className="max-w-md mx-auto">
          <textarea
            value={comment}
            onChange={(e) => setComment(e.target.value)}
            placeholder="What could be improved?"
            rows={3}
            className="w-full rounded-lg border border-slate-700 bg-slate-900 px-3 py-2 text-sm text-slate-200 placeholder:text-slate-500 focus:border-sky-500 focus:outline-none resize-none"
          />
          <div className="flex justify-end mt-2">
            <button
              onClick={handleSubmitFeedback}
              className="px-4 py-1.5 text-sm font-medium text-white bg-sky-500 hover:bg-sky-600 rounded-lg transition-colors"
            >
              Submit feedback
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
