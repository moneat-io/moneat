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

import {useCallback, useState} from 'react'

export interface SeriesVisibility {
  hidden: ReadonlySet<string>
  toggle: (key: string, additive: boolean) => void
}

const EMPTY_HIDDEN: ReadonlySet<string> = new Set()

/**
 * Tracks which chart series are hidden so a legend can filter the chart.
 * A plain legend click isolates that series (hides every other one); clicking
 * the already-isolated series restores all. A modifier click (Cmd/Ctrl/Shift)
 * toggles a single series so custom subsets can be built. Visibility resets when
 * the set of series changes (e.g. a new query result shape) so we never strand a
 * key that is no longer rendered.
 */
export function useSeriesVisibility(seriesKeys: string[]): SeriesVisibility {
  const [hidden, setHidden] = useState<ReadonlySet<string>>(EMPTY_HIDDEN)
  const signature = JSON.stringify(seriesKeys)

  // Reset visibility when the set of series changes. Adjusting state during
  // render is React's sanctioned way to reset on a prop change; an effect here
  // would instead trigger a cascading render.
  const [prevSignature, setPrevSignature] = useState(signature)
  if (prevSignature !== signature) {
    setPrevSignature(signature)
    setHidden(EMPTY_HIDDEN)
  }

  const toggle = useCallback(
    (key: string, additive: boolean) => {
      setHidden((prev) => {
        if (additive) {
          const next = new Set(prev)
          if (next.has(key)) next.delete(key)
          else next.add(key)
          // Never hide every series — that just blanks the chart.
          return next.size >= seriesKeys.length ? prev : next
        }
        // Plain click isolates; clicking the already-isolated series restores all.
        const alreadyIsolated = prev.size === seriesKeys.length - 1 && !prev.has(key)
        return alreadyIsolated ? EMPTY_HIDDEN : new Set(seriesKeys.filter((k) => k !== key))
      })
    },
    [seriesKeys],
  )

  return {hidden, toggle}
}
