import { Moon, Sun, Palette, CloudMoon, Leaf, Sunset } from 'lucide-react'
import { Button } from './ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from './ui/dropdown-menu'
import { useEffect, useState } from 'react'

type Theme = 'light' | 'dark' | 'midnight' | 'forest' | 'sunset'

export function ThemeSwitcher() {
  const [theme, setTheme] = useState<Theme>('dark')

  useEffect(() => {
    const savedTheme = localStorage.getItem('theme') as Theme | null
    
    if (savedTheme) {
      setTheme(savedTheme)
      applyTheme(savedTheme)
    } else {
      setTheme('dark')
      applyTheme('dark')
    }
  }, [])

  const applyTheme = (newTheme: Theme) => {
    const root = window.document.documentElement
    
    // Remove all theme classes
    root.classList.remove('light', 'dark', 'theme-midnight', 'theme-forest', 'theme-sunset')

    if (newTheme === 'light') {
      // No class for light mode (default)
    } else if (newTheme === 'dark') {
      root.classList.add('dark')
    } else {
      // For other themes, they are dark-based, so add 'dark' and the theme class
      root.classList.add('dark', `theme-${newTheme}`)
    }
  }

  const handleThemeChange = (newTheme: Theme) => {
    setTheme(newTheme)
    applyTheme(newTheme)
    localStorage.setItem('theme', newTheme)
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="icon" aria-label="Select theme">
          <Palette className="h-5 w-5" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <DropdownMenuItem onClick={() => handleThemeChange('light')}>
          <Sun className="mr-2 h-4 w-4" />
          <span>Light</span>
        </DropdownMenuItem>
        <DropdownMenuItem onClick={() => handleThemeChange('dark')}>
          <Moon className="mr-2 h-4 w-4" />
          <span>Dark</span>
        </DropdownMenuItem>
        <DropdownMenuItem onClick={() => handleThemeChange('midnight')}>
          <CloudMoon className="mr-2 h-4 w-4" />
          <span>Midnight</span>
        </DropdownMenuItem>
        <DropdownMenuItem onClick={() => handleThemeChange('forest')}>
          <Leaf className="mr-2 h-4 w-4" />
          <span>Forest</span>
        </DropdownMenuItem>
        <DropdownMenuItem onClick={() => handleThemeChange('sunset')}>
          <Sunset className="mr-2 h-4 w-4" />
          <span>Sunset</span>
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
