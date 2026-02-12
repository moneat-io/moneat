import {createFileRoute} from '@tanstack/react-router'
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from '@/components/ui/card'
import {Button} from '@/components/ui/button'
import {ListChecks} from 'lucide-react'

export const Route = createFileRoute('/on-call/escalation-policies')({
  component: EscalationPolicies,
})

function EscalationPolicies() {
  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-2xl font-bold">Escalation Policies</h2>
          <p className="text-muted-foreground">Define how incidents escalate through your team</p>
        </div>
        <Button>
          <ListChecks className="h-4 w-4 mr-2" />
          Create Policy
        </Button>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Escalation Policies</CardTitle>
          <CardDescription>Coming soon - visual escalation policy editor</CardDescription>
        </CardHeader>
        <CardContent>
          <p className="text-sm text-muted-foreground">
            This page will provide a drag-and-drop editor for creating escalation policies.
          </p>
        </CardContent>
      </Card>
    </div>
  )
}
