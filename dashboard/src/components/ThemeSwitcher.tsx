import {Check, Palette} from 'lucide-react'
import {Button} from './ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from './ui/dropdown-menu'
import {THEME_OPTIONS, useTheme} from '@/lib/theme'

/** Compact theme picker for the sidebar — a palette dropdown of every theme. */
export function ThemeSwitcher() {
  const {theme, setTheme} = useTheme()

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="icon" aria-label="Select theme">
          <Palette className="h-5 w-5" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        {THEME_OPTIONS.map((option) => {
          const Icon = option.icon
          return (
            <DropdownMenuItem key={option.id} onClick={() => setTheme(option.id)}>
              <Icon className="mr-2 h-4 w-4" />
              <span>{option.label}</span>
              {theme === option.id && (
                <Check className="ml-auto h-4 w-4" data-testid="check-icon" />
              )}
            </DropdownMenuItem>
          )
        })}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
