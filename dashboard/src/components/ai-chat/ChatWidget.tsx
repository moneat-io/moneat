// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

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
