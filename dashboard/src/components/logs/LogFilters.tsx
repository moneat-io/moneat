import {Input} from '@/components/ui/input'
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from '@/components/ui/select'
import {Button} from '@/components/ui/button'
import {Card, CardContent} from '@/components/ui/card'
import {Search} from 'lucide-react'
import {cn} from '@/lib/utils'

interface LogFiltersProps {
  query: string
  onQueryChange: (value: string) => void
  service: string
  onServiceChange: (value: string) => void
  environment: string
  onEnvironmentChange: (value: string) => void
  levels: string[]
  onToggleLevel: (level: string) => void
  availableServices: string[]
  availableEnvironments: string[]
  from: string
  onFromChange: (value: string) => void
  to: string
  onToChange: (value: string) => void
  tagFilter: string
  onTagFilterChange: (value: string) => void
}

const levelOptions = ['trace', 'debug', 'info', 'warn', 'error', 'fatal']

export function LogFilters({
  query,
  onQueryChange,
  service,
  onServiceChange,
  environment,
  onEnvironmentChange,
  levels,
  onToggleLevel,
  availableServices,
  availableEnvironments,
  from,
  onFromChange,
  to,
  onToChange,
  tagFilter,
  onTagFilterChange,
}: LogFiltersProps) {
  return (
    <Card>
      <CardContent className="p-4 space-y-3">
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
          <div className="relative xl:col-span-2">
            <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              value={query}
              onChange={(event) => onQueryChange(event.target.value)}
              placeholder="Search message/body"
              className="pl-9"
            />
          </div>

          <Input
            value={tagFilter}
            onChange={(event) => onTagFilterChange(event.target.value)}
            placeholder="Tags: key:value,key2:value2"
          />

          <div className="grid grid-cols-2 gap-2">
            <Input
              type="datetime-local"
              value={from}
              onChange={(event) => onFromChange(event.target.value)}
              aria-label="From timestamp"
            />
            <Input
              type="datetime-local"
              value={to}
              onChange={(event) => onToChange(event.target.value)}
              aria-label="To timestamp"
            />
          </div>
        </div>

        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
          <Select value={service} onValueChange={onServiceChange}>
            <SelectTrigger>
              <SelectValue placeholder="All services" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">All services</SelectItem>
              {availableServices.map((item) => (
                <SelectItem key={item} value={item}>{item}</SelectItem>
              ))}
            </SelectContent>
          </Select>

          <Select value={environment} onValueChange={onEnvironmentChange}>
            <SelectTrigger>
              <SelectValue placeholder="All environments" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">All environments</SelectItem>
              {availableEnvironments.map((item) => (
                <SelectItem key={item} value={item}>{item}</SelectItem>
              ))}
            </SelectContent>
          </Select>

          <div className="flex flex-wrap gap-1.5 xl:col-span-2">
            {levelOptions.map((level) => {
              const active = levels.includes(level)
              return (
                <Button
                  key={level}
                  type="button"
                  size="sm"
                  variant={active ? 'default' : 'outline'}
                  onClick={() => onToggleLevel(level)}
                  className={cn('font-mono uppercase text-[11px] h-8', !active && 'text-muted-foreground')}
                >
                  {level}
                </Button>
              )
            })}
          </div>
        </div>
      </CardContent>
    </Card>
  )
}
