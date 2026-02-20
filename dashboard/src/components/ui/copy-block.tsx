import {useEffect, useState} from 'react'
import {Check, Copy} from 'lucide-react'
import {Prism as SyntaxHighlighter} from 'react-syntax-highlighter'
import {oneDark, oneLight} from 'react-syntax-highlighter/dist/esm/styles/prism'

const LANGUAGE_ALIASES: Record<string, string> = {
  xml: 'markup',
  html: 'markup',
  text: 'plaintext',
}

interface CopyBlockProps {
  code: string
  language: string
}

export function CopyBlock({code, language}: CopyBlockProps) {
  const [copied, setCopied] = useState(false)
  const [isDark, setIsDark] = useState(() => document.documentElement.classList.contains('dark'))

  useEffect(() => {
    const root = document.documentElement
    const observer = new MutationObserver(() => setIsDark(root.classList.contains('dark')))
    observer.observe(root, {attributes: true, attributeFilter: ['class']})
    return () => observer.disconnect()
  }, [])

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(code)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch {
      // Ignore clipboard errors
    }
  }

  const prismLanguage = LANGUAGE_ALIASES[language] ?? language ?? 'plaintext'
  const style = isDark ? oneDark : oneLight

  return (
    <div className="group relative overflow-hidden rounded-lg border bg-card">
      <div className="flex items-center justify-between border-b bg-muted/40 px-4 py-2">
        <span className="font-mono text-[11px] text-muted-foreground">{language}</span>
        <button
          type="button"
          onClick={handleCopy}
          className="flex items-center gap-1.5 rounded-md px-2 py-1 text-[11px] text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
        >
          {copied ? (
            <>
              <Check className="h-3 w-3 text-emerald-500" />
              Copied
            </>
          ) : (
            <>
              <Copy className="h-3 w-3" />
              Copy
            </>
          )}
        </button>
      </div>
      <SyntaxHighlighter
        language={prismLanguage}
        style={style}
        customStyle={{
          margin: 0,
          padding: '1rem',
          fontSize: '0.75rem',
          lineHeight: 1.6,
          background: undefined,
        }}
        codeTagProps={{
          style: {
            fontFamily: 'ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace',
          },
        }}
        showLineNumbers={code.split('\n').length > 3}
        lineNumberStyle={{minWidth: '2em', paddingRight: '1em', opacity: 0.5}}
        wrapLongLines
      >
        {code}
      </SyntaxHighlighter>
    </div>
  )
}
