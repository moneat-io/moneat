import {createFileRoute} from '@tanstack/react-router'
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from '@/components/ui/card'
import {Button} from '@/components/ui/button'
import {Calendar} from 'lucide-react'

export const Route = createFileRoute('/on-call/schedules')({
  component: OnCallSchedules,
})

function OnCallSchedules() {
  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-2xl font-bold">On-Call Schedules</h2>
          <p className="text-muted-foreground">Manage rotation schedules and participants</p>
        </div>
        <Button>
          <Calendar className="h-4 w-4 mr-2" />
          Create Schedule
        </Button>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Schedules</CardTitle>
          <CardDescription>Coming soon - schedule management interface</CardDescription>
        </CardHeader>
        <CardContent>
          <p className="text-sm text-muted-foreground">
            This page will allow you to create and manage on-call rotation schedules.
          </p>
        </CardContent>
      </Card>
    </div>
  )
}
