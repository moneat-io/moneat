import {createFileRoute} from '@tanstack/react-router'
import {useMutation} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {useState} from 'react'
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from '@/components/ui/card'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from '@/components/ui/select'
import {useToast} from '@/hooks/use-toast'
import {
  AlertTriangle,
  Server,
  Activity,
  AlertCircle
} from 'lucide-react'
import {SectionHeader} from '@/components/admin-components'

export const Route = createFileRoute('/admin/incidents')({
  component: AdminIncidentsPage,
})

const alertSources = [
  { value: 'SYSTEM_DOWN', label: 'System Down', icon: Server, description: 'Simulate a system outage' },
  { value: 'UPTIME_MONITOR', label: 'Uptime Monitor', icon: Activity, description: 'Simulate an uptime check failure' },
  { value: 'ERROR_ALERT', label: 'Error Alert', icon: AlertCircle, description: 'Simulate a critical error spike' },
  { value: 'SYSTEM_ALERT', label: 'System Alert', icon: AlertTriangle, description: 'Generic system alert' },
]

const severities = [
  { value: 'CRITICAL', label: 'Critical' },
  { value: 'HIGH', label: 'High' },
  { value: 'MEDIUM', label: 'Medium' },
  { value: 'LOW', label: 'Low' },
]

function AdminIncidentsPage() {
  const { toast } = useToast()
  const [source, setSource] = useState('SYSTEM_DOWN')
  const [severity, setSeverity] = useState('CRITICAL')
  const [title, setTitle] = useState('Test Incident: System Unresponsive')
  const [description, setDescription] = useState('This is a manually triggered test incident.')
  
  const triggerMutation = useMutation({
    mutationFn: async () => {
      return api.triggerIncident({
        source,
        severity,
        title,
        description
      })
    },
    onSuccess: () => {
      toast({
        title: 'Incident Triggered',
        description: 'The incident has been successfully triggered.',
      })
    },
    onError: (error) => {
      toast({
        title: 'Failed to trigger incident',
        description: error instanceof Error ? error.message : 'An error occurred',
        variant: 'destructive',
      })
    },
  })

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    triggerMutation.mutate()
  }

  return (
    <div className="space-y-8">
      <SectionHeader
        title="Incident Management"
        description="Manually trigger incidents to test alerting and escalation policies."
      />

      <Card>
        <CardHeader>
          <CardTitle>Trigger New Incident</CardTitle>
          <CardDescription>
            Configure the parameters below to fire a test incident.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <form onSubmit={handleSubmit} className="space-y-6 max-w-xl">
            <div className="space-y-2">
              <Label>Incident Source</Label>
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                {alertSources.map((item) => {
                  const Icon = item.icon
                  return (
                    <div 
                      key={item.value}
                      className={`
                        cursor-pointer border rounded-lg p-4 flex items-start gap-3 transition-colors hover:bg-muted/50
                        ${source === item.value ? 'border-primary bg-primary/5 ring-1 ring-primary' : ''}
                      `}
                      onClick={() => setSource(item.value)}
                    >
                      <div className={`rounded-md p-2 ${source === item.value ? 'bg-primary text-primary-foreground' : 'bg-muted'}`}>
                        <Icon className="h-4 w-4" />
                      </div>
                      <div>
                        <div className="font-medium text-sm">{item.label}</div>
                        <div className="text-xs text-muted-foreground mt-1">{item.description}</div>
                      </div>
                    </div>
                  )
                })}
              </div>
            </div>

            <div className="space-y-2">
              <Label htmlFor="severity">Severity</Label>
              <Select value={severity} onValueChange={setSeverity}>
                <SelectTrigger id="severity">
                  <SelectValue placeholder="Select severity" />
                </SelectTrigger>
                <SelectContent>
                  {severities.map((s) => (
                    <SelectItem key={s.value} value={s.value}>
                      {s.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            <div className="space-y-2">
              <Label htmlFor="title">Incident Title</Label>
              <Input 
                id="title" 
                value={title} 
                onChange={(e) => setTitle(e.target.value)} 
                placeholder="e.g. Database Connection Failed"
              />
            </div>

            <div className="space-y-2">
              <Label htmlFor="description">Description</Label>
              <Input 
                id="description" 
                value={description} 
                onChange={(e) => setDescription(e.target.value)} 
                placeholder="Detailed description of the incident..."
              />
            </div>

            <Button type="submit" disabled={triggerMutation.isPending}>
              {triggerMutation.isPending ? 'Triggering...' : 'Trigger Incident'}
            </Button>
          </form>
        </CardContent>
      </Card>
    </div>
  )
}
