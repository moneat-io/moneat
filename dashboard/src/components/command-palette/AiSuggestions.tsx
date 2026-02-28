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
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
// See the GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

const DEFAULT_SUGGESTIONS = [
  'What errors happened in the last hour?',
  'Show me the slowest endpoints',
  'Are there any active incidents?',
  'Which hosts have high CPU usage?',
  'Check uptime monitor status',
]

interface AiSuggestionsProps {
  suggestions?: string[]
  onSelect: (value: string) => void
}

export function AiSuggestions({suggestions = DEFAULT_SUGGESTIONS, onSelect}: AiSuggestionsProps) {
  return (
    <div className="flex flex-wrap gap-2 px-3 py-3">
      {suggestions.map((suggestion) => (
        <button
          key={suggestion}
          type="button"
          onClick={() => onSelect(suggestion)}
          className="rounded-full border border-border bg-muted/70 px-3 py-1.5 text-xs text-left hover:bg-muted transition-colors"
        >
          {suggestion}
        </button>
      ))}
    </div>
  )
}
