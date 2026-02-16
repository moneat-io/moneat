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

import {Moon, Sun} from 'lucide-react'
import {Button} from './ui/button'
import {useEffect, useState} from 'react'

export function ThemeToggle() {
  const [theme, setTheme] = useState<'dark' | 'light'>('dark')

  useEffect(() => {
    const root = window.document.documentElement
    const savedTheme = localStorage.getItem('theme') as 'dark' | 'light' | null
    
    if (savedTheme) {
      setTheme(savedTheme)
      root.classList.toggle('dark', savedTheme === 'dark')
    } else {
      // Default to dark theme
      setTheme('dark')
      root.classList.add('dark')
      localStorage.setItem('theme', 'dark')
    }
  }, [])

  const toggleTheme = () => {
    const newTheme = theme === 'dark' ? 'light' : 'dark'
    const root = window.document.documentElement
    
    root.classList.toggle('dark', newTheme === 'dark')
    setTheme(newTheme)
    localStorage.setItem('theme', newTheme)
  }

  return (
    <Button
      variant="ghost"
      size="icon"
      onClick={toggleTheme}
      aria-label="Toggle theme"
    >
      {theme === 'dark' ? (
        <Sun className="h-5 w-5" />
      ) : (
        <Moon className="h-5 w-5" />
      )}
    </Button>
  )
}
