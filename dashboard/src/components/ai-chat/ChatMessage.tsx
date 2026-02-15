import {Logo} from '@/components/logo'
import {cn} from '@/lib/utils'

interface ChatMessageProps {
  role: 'user' | 'assistant'
  content: string
}

export function ChatMessage({role, content}: ChatMessageProps) {
  if (role === 'user') {
    return (
      <div className="flex justify-end">
        <div className="bg-primary text-primary-foreground rounded-lg px-3 py-2 text-sm max-w-[80%]">
          {content}
        </div>
      </div>
    )
  }

  return (
    <div className="flex items-start gap-2">
      <div className="flex-shrink-0 w-7 h-7 rounded-full bg-primary/10 flex items-center justify-center">
        <Logo markOnly className="h-4 w-4" />
      </div>
      <div className={cn("bg-muted rounded-lg px-3 py-2 text-sm max-w-[85%]", "whitespace-pre-wrap")}>
        {content}
      </div>
    </div>
  )
}
