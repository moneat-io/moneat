import {Check, Copy} from 'lucide-react'
import {useState, useCallback} from 'react'

interface CodeBlockProps {
  code: string
  language?: string
  title?: string
}

export function CodeBlock({code, language, title}: CodeBlockProps) {
  const [copied, setCopied] = useState(false)

  const handleCopy = useCallback(() => {
    navigator.clipboard.writeText(code)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }, [code])

  return (
    <div className="rounded-lg border bg-zinc-950 text-zinc-100 overflow-hidden">
      {(title || language) && (
        <div className="flex items-center justify-between px-4 py-2 border-b border-zinc-800 bg-zinc-900/50">
          <span className="text-xs text-zinc-400 font-medium">{title || language}</span>
          <button
            onClick={handleCopy}
            className="flex items-center gap-1.5 text-xs text-zinc-400 hover:text-zinc-200 transition-colors"
          >
            {copied ? <Check className="h-3.5 w-3.5" /> : <Copy className="h-3.5 w-3.5" />}
            {copied ? 'Copied' : 'Copy'}
          </button>
        </div>
      )}
      <pre className="p-4 overflow-x-auto text-sm leading-relaxed">
        <code>{code}</code>
      </pre>
    </div>
  )
}

export function InlineCode({children}: {children: React.ReactNode}) {
  return (
    <code className="px-1.5 py-0.5 rounded bg-muted text-sm font-mono text-foreground">
      {children}
    </code>
  )
}
