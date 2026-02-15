import type {AiClarification} from '@/lib/api'
import {cn} from '@/lib/utils'

interface ClarificationCardProps {
  clarification: AiClarification
  onSelect: (value: string) => void
}

export function ClarificationCard({clarification, onSelect}: ClarificationCardProps) {
  return (
    <div className="ml-9 mt-2 border border-border rounded-lg p-3 bg-card text-sm">
      <p className="mb-2">{clarification.question}</p>
      <div className="flex flex-wrap gap-1.5">
        {clarification.options?.map(option => (
          <button
            key={option.value}
            onClick={() => onSelect(option.value)}
            className={cn(
              "px-2.5 py-1 text-xs rounded-full border border-border hover:bg-primary hover:text-primary-foreground hover:border-primary transition-colors",
              option.value === clarification.default && "border-primary/50 bg-primary/5"
            )}
          >
            {option.label}
          </button>
        ))}
      </div>
    </div>
  )
}
