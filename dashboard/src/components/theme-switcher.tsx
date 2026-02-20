import { Moon, Sun, Palette, CloudMoon, Leaf, Sunset, Gamepad2, Check } from 'lucide-react'
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
  const [theme, setTheme] = useState<Theme>(() => {
    const saved = localStorage.getItem('theme') as Theme | null
    return saved || 'dark'
  })

  const applyTheme = (newTheme: Theme) => {
    const root = window.document.documentElement
    
    // Remove all theme classes
    root.classList.remove('light', 'dark', 'theme-midnight', 'theme-forest', 'theme-sunset', 'theme-gamer')

    if (newTheme === 'light') {
      // No class for light mode (default)
    } else if (newTheme === 'dark') {
      root.classList.add('dark')
    } else {
      // For other themes, they are dark-based, so add 'dark' and the theme class
      root.classList.add('dark', `theme-${newTheme}`)
    }
  }

  useEffect(() => {
    applyTheme(theme)
  }, [theme])

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
          {theme === 'light' && <Check className="ml-auto h-4 w-4" />}
        </DropdownMenuItem>
        <DropdownMenuItem onClick={() => handleThemeChange('dark')}>
          <Moon className="mr-2 h-4 w-4" />
          <span>Dark</span>
          {theme === 'dark' && <Check className="ml-auto h-4 w-4" />}
        </DropdownMenuItem>
        <DropdownMenuItem onClick={() => handleThemeChange('midnight')}>
          <CloudMoon className="mr-2 h-4 w-4" />
          <span>Midnight</span>
          {theme === 'midnight' && <Check className="ml-auto h-4 w-4" />}
        </DropdownMenuItem>
        <DropdownMenuItem onClick={() => handleThemeChange('forest')}>
          <Leaf className="mr-2 h-4 w-4" />
          <span>Forest</span>
          {theme === 'forest' && <Check className="ml-auto h-4 w-4" />}
        </DropdownMenuItem>
        <DropdownMenuItem onClick={() => handleThemeChange('sunset')}>
          <Sunset className="mr-2 h-4 w-4" />
          <span>Sunset</span>
          {theme === 'sunset' && <Check className="ml-auto h-4 w-4" />}
        </DropdownMenuItem>
        <DropdownMenuItem onClick={() => handleThemeChange('gamer')}>
          <Gamepad2 className="mr-2 h-4 w-4" />
          <span>Gamer</span>
          {theme === 'gamer' && <Check className="ml-auto h-4 w-4" />}
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
