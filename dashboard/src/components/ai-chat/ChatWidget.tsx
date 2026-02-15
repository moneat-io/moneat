import {useState} from 'react'
import {ChatPanel} from './ChatPanel'
import {Logo} from '@/components/logo'

export function ChatWidget() {
  const [isOpen, setIsOpen] = useState(false)

  return (
    <>
      {isOpen && (
        <ChatPanel
          onClose={() => setIsOpen(false)}
          onMinimize={() => setIsOpen(false)}
        />
      )}
      <button
        onClick={() => setIsOpen(!isOpen)}
        className="fixed bottom-4 right-4 z-50 w-12 h-12 rounded-full bg-primary text-primary-foreground shadow-lg hover:shadow-xl transition-all hover:scale-105 flex items-center justify-center"
        title="Moneat AI"
      >
        <Logo markOnly className="h-6 w-6 [&_circle]:stroke-primary-foreground [&_polyline]:stroke-primary-foreground" />
      </button>
    </>
  )
}
