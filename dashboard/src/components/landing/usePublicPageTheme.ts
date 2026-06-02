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

import {useLayoutEffect} from 'react'

// The public marketing pages are dark-first (style-guide). Hold the document in
// dark mode while such a page is mounted, and restore the prior theme when the
// visitor navigates back into the (light-by-default) app.
export function useForceDarkTheme() {
  useLayoutEffect(() => {
    const root = document.documentElement
    const hadDark = root.classList.contains('dark')
    const prevColorScheme = root.style.colorScheme
    root.classList.add('dark')
    root.style.colorScheme = 'dark'
    return () => {
      if (!hadDark) root.classList.remove('dark')
      root.style.colorScheme = prevColorScheme
    }
  }, [])
}
