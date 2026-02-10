import {createFileRoute, redirect} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {Button} from '@/components/ui/button'
import {Card, CardContent} from '@/components/ui/card'
import {Separator} from '@/components/ui/separator'
import {Activity, CheckCircle2, Globe, Pause, Plus, XCircle} from 'lucide-react'
import {useState} from 'react'
import AddMonitorDialog from '@/components/uptime/add-monitor-dialog'
import MonitorListItem from '@/components/uptime/monitor-list-item'

export const Route = createFileRoute('/uptime/')({
  beforeLoad: () => {
    if (!api.isAuthenticated()) {
      throw redirect({to: '/login'})
    }
  },
  component: UptimeListPage,
})

function UptimeListPage() {
  const [addDialogOpen, setAddDialogOpen] = useState(false)

  const {data: monitors = [], isLoading} = useQuery({
    queryKey: ['uptime-monitors'],
    queryFn: () => api.getUptimeMonitors(),
  })

  const upMonitors = monitors.filter((m) => m.status === 'up').length
  const downMonitors = monitors.filter((m) => m.status === 'down').length
  const pausedMonitors = monitors.filter((m) => m.status === 'paused').length

  if (isLoading) {
    return (
      <div className="container mx-auto px-4 py-8">
        <div className="flex items-center justify-center h-64">
          <Activity className="h-8 w-8 animate-spin text-muted-foreground" />
        </div>
      </div>
    )
  }

  return (
    <div className="min-h-screen bg-background">
      {/* Header */}
      <div className="border-b bg-card/50">
        <div className="container mx-auto px-4 py-6">
          <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
            <div>
              <h1 className="text-3xl font-bold tracking-tight">Uptime Monitoring</h1>
              <p className="text-muted-foreground mt-1">
                Monitor your websites, APIs, and services
              </p>
            </div>
            <Button onClick={() => setAddDialogOpen(true)}>
              <Plus className="mr-2 h-4 w-4" />
              Add Monitor
            </Button>
          </div>
        </div>
      </div>

      <div className="container mx-auto px-4 py-6 space-y-6">
        {/* Summary Stats */}
        {monitors.length > 0 && (
          <div className="grid gap-4 grid-cols-2 md:grid-cols-4">
            <Card className="bg-gradient-to-br from-blue-500/5 to-blue-600/10 border-blue-500/10">
              <CardContent className="pt-5 pb-4">
                <div className="flex items-center gap-3">
                  <div className="flex items-center justify-center h-10 w-10 rounded-lg bg-blue-500/15">
                    <Globe className="h-5 w-5 text-blue-600 dark:text-blue-400" />
                  </div>
                  <div>
                    <p className="text-2xl font-bold">{monitors.length}</p>
                    <p className="text-xs text-muted-foreground">Total Monitors</p>
                  </div>
                </div>
              </CardContent>
            </Card>

            <Card className="bg-gradient-to-br from-emerald-500/5 to-emerald-600/10 border-emerald-500/10">
              <CardContent className="pt-5 pb-4">
                <div className="flex items-center gap-3">
                  <div className="flex items-center justify-center h-10 w-10 rounded-lg bg-emerald-500/15">
                    <CheckCircle2 className="h-5 w-5 text-emerald-600 dark:text-emerald-400" />
                  </div>
                  <div>
                    <p className="text-2xl font-bold">{upMonitors}</p>
                    <p className="text-xs text-muted-foreground">Up</p>
                  </div>
                </div>
              </CardContent>
            </Card>

            <Card className="bg-gradient-to-br from-red-500/5 to-red-600/10 border-red-500/10">
              <CardContent className="pt-5 pb-4">
                <div className="flex items-center gap-3">
                  <div className="flex items-center justify-center h-10 w-10 rounded-lg bg-red-500/15">
                    <XCircle className="h-5 w-5 text-red-600 dark:text-red-400" />
                  </div>
                  <div>
                    <p className="text-2xl font-bold">{downMonitors}</p>
                    <p className="text-xs text-muted-foreground">Down</p>
                  </div>
                </div>
              </CardContent>
            </Card>

            <Card className="bg-gradient-to-br from-yellow-500/5 to-yellow-600/10 border-yellow-500/10">
              <CardContent className="pt-5 pb-4">
                <div className="flex items-center gap-3">
                  <div className="flex items-center justify-center h-10 w-10 rounded-lg bg-yellow-500/15">
                    <Pause className="h-5 w-5 text-yellow-600 dark:text-yellow-400" />
                  </div>
                  <div>
                    <p className="text-2xl font-bold">{pausedMonitors}</p>
                    <p className="text-xs text-muted-foreground">Paused</p>
                  </div>
                </div>
              </CardContent>
            </Card>
          </div>
        )}

        {monitors.length === 0 ? (
          <Card>
            <CardContent className="flex flex-col items-center justify-center py-16">
              <Globe className="h-16 w-16 text-muted-foreground mb-4" />
              <h3 className="text-lg font-semibold mb-2">No monitors yet</h3>
              <p className="text-muted-foreground text-center mb-6">
                Get started by creating your first uptime monitor
              </p>
              <Button onClick={() => setAddDialogOpen(true)}>
                <Plus className="mr-2 h-4 w-4" />
                Create Monitor
              </Button>
            </CardContent>
          </Card>
        ) : (
          <div className="grid gap-4">
            {monitors.map((monitor) => (
              <MonitorListItem key={monitor.id} monitor={monitor} />
            ))}
          </div>
        )}
      </div>

      <AddMonitorDialog open={addDialogOpen} onOpenChange={setAddDialogOpen} />
    </div>
  )
}
