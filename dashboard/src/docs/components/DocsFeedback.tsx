import {useState} from 'react'
import {ThumbsUp, ThumbsDown} from 'lucide-react'
import * as Sentry from '@sentry/react'
import {Button} from '@/components/ui/button'
import {Textarea} from '@/components/ui/textarea'
import {Label} from '@/components/ui/label'

type FeedbackState = 'idle' | 'thumbs-up' | 'thumbs-down' | 'submitted'

export function DocsFeedback({slug}: {slug: string}) {
  const [state, setState] = useState<FeedbackState>('idle')
  const [comment, setComment] = useState('')

  const handleThumbsDown = () => setState('thumbs-down')

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
      <div className="mt-12 pt-8 border-t border-slate-800 text-center">
        <p className="text-sm text-slate-400">
          {state === 'thumbs-up' ? 'Glad it was helpful!' : 'Thanks for your feedback!'}
        </p>
      </div>
    )
  }

  return (
    <div className="mt-12 pt-8 border-t border-slate-800">
      <p className="text-sm text-slate-400 text-center mb-3">Was this page helpful?</p>

      <div className="flex justify-center gap-3 mb-4">
        <Button
          variant="outline"
          size="sm"
          onClick={() => setState('thumbs-up')}
          className="border-slate-700 text-slate-300 hover:border-sky-500 hover:text-sky-400"
        >
          <ThumbsUp className="h-4 w-4" /> Yes
        </Button>
        <Button
          variant="outline"
          size="sm"
          onClick={handleThumbsDown}
          aria-expanded={state === 'thumbs-down'}
          className="border-slate-700 text-slate-300 hover:border-sky-500 hover:text-sky-400"
        >
          <ThumbsDown className="h-4 w-4" /> No
        </Button>
      </div>

      {state === 'thumbs-down' && (
        <div className="max-w-md mx-auto">
          <Label htmlFor="docs-feedback-comment" className="sr-only">
            What could be improved?
          </Label>
          <Textarea
            id="docs-feedback-comment"
            value={comment}
            onChange={(e) => setComment(e.target.value)}
            placeholder="What could be improved?"
            rows={3}
            className="border-slate-700 bg-slate-900 text-slate-200 placeholder:text-slate-500 focus-visible:ring-sky-500 resize-none"
          />
          <div className="flex justify-end mt-2">
            <Button size="sm" onClick={handleSubmitFeedback}>
              Submit feedback
            </Button>
          </div>
        </div>
      )}
    </div>
  )
}
