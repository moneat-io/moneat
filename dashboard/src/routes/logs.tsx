import {createFileRoute, redirect} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {useProject} from '@/contexts/project-context'
import {LogExplorer} from '@/components/logs/LogExplorer'
import {parseLogViewSearch, type LogViewSearch} from '@/components/logs/logViewUrlState'
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from '@/components/ui/select'
import {ScrollText} from 'lucide-react'

export const Route = createFileRoute('/logs')({
  validateSearch: (search: Record<string, unknown>): LogViewSearch => {
    return parseLogViewSearch(search)
  },
  beforeLoad: async () => {
    if (!api.isAuthenticated()) {
      throw redirect({to: '/login'})
    }
  },
  component: LogsPage,
})

function LogsPage() {
  const search = Route.useSearch()
  const {selectedProjectId, setSelectedProjectId} = useProject()

  const {data: projects = []} = useQuery({
    queryKey: ['projects'],
    queryFn: () => api.getProjects(),
  })

  const {data: sdkVersionsResponse} = useQuery({
    queryKey: ['sdk-versions'],
    queryFn: () => api.getSdkVersions(),
    staleTime: 30 * 60 * 1000,
  })

  const projectId = selectedProjectId || projects?.[0]?.id
  const currentProject = projects.find(p => p.id === projectId)

  if (!projectId || projects.length === 0) {
    return (
      <div className="flex h-screen items-center justify-center">
        <div className="text-center space-y-4 max-w-md">
          <div className="rounded-full bg-muted/60 p-4 mx-auto w-fit">
            <ScrollText className="h-10 w-10 text-muted-foreground" />
          </div>
          <h2 className="text-xl font-semibold">No projects available</h2>
          <p className="text-muted-foreground">Create a project to start viewing logs.</p>
        </div>
      </div>
    )
  }

  return (
    <div className="flex h-screen flex-col overflow-hidden">
      <div className="flex items-center gap-3 px-4 py-2 border-b bg-card/50">
        <span className="text-sm text-muted-foreground">Project:</span>
        <Select
          value={String(projectId)}
          onValueChange={(val) => setSelectedProjectId(Number(val))}
        >
          <SelectTrigger className="w-[220px] h-8">
            <SelectValue placeholder="Select project" />
          </SelectTrigger>
          <SelectContent>
            {projects.map((p) => (
              <SelectItem key={p.id} value={String(p.id)}>
                {p.name}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>
      <LogExplorer
        projectId={projectId}
        dsn={currentProject?.dsn}
        sdkVersions={sdkVersionsResponse?.versions}
        className="h-full"
        enableUrlSync={true}
        urlSearch={search}
      />
    </div>
  )
}
