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

import {useEffect, useState} from 'react'

// Matches Tailwind's `md` breakpoint: anything below 768px is treated as mobile,
// driving the off-canvas navigation drawer and full-width content shell.
const MOBILE_BREAKPOINT = 768

const MOBILE_QUERY = `(max-width: ${MOBILE_BREAKPOINT - 1}px)`

function getMatches(): boolean {
  const browserWindow = globalThis.window
  if (typeof browserWindow === 'undefined' || typeof browserWindow.matchMedia !== 'function') return false
  return browserWindow.matchMedia(MOBILE_QUERY).matches
}

/** True when the viewport is narrower than the `md` breakpoint. Updates on resize. */
export function useIsMobile(): boolean {
  const [isMobile, setIsMobile] = useState(getMatches)

  useEffect(() => {
    const browserWindow = globalThis.window
    if (typeof browserWindow === 'undefined' || typeof browserWindow.matchMedia !== 'function') {
      return
    }

    const mql = browserWindow.matchMedia(MOBILE_QUERY)
    const onChange = () => setIsMobile(mql.matches)
    onChange()
    mql.addEventListener('change', onChange)
    return () => mql.removeEventListener('change', onChange)
  }, [])

  return isMobile
}
