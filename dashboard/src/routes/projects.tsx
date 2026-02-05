import { createFileRoute, redirect, Link } from '@tanstack/react-router'
import { api } from '@/lib/api'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Card, CardContent } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Badge } from '@/components/ui/badge'
import { Plus, Smartphone, Globe, Code2, Terminal, Settings } from 'lucide-react'
import { useState } from 'react'
import { formatRelativeTime } from '@/lib/utils'
import { getProjectColor, getProjectInitial } from '@/lib/project-colors'

// Helper function to get platform info (with fallbacks for different naming conventions)
function getPlatformInfo(platformId?: string) {
  if (!platformId) return null
  
  // Direct match
  let platform = platforms.find(p => p.id === platformId)
  if (platform) return platform
  
  // Try case-insensitive match
  const lowerPlatform = platformId.toLowerCase()
  platform = platforms.find(p => p.id.toLowerCase() === lowerPlatform)
  if (platform) return platform
  
  // Common aliases
  const aliases: Record<string, string> = {
    'kotlin': 'kmp',
    'kotlin-multiplatform': 'kmp',
    'kotlinmultiplatform': 'kmp',
    'javascript': 'web',
    'js': 'web',
    'typescript': 'web',
    'ts': 'web',
  }
  
  const aliasMatch = aliases[lowerPlatform]
  if (aliasMatch) {
    return platforms.find(p => p.id === aliasMatch) || null
  }
  
  return null
}

// Platform type definitions
type PlatformType = {
  id: string
  name: string
  description: string
  icon: React.ComponentType<{ className?: string }>
  color: string
  category: 'mobile' | 'web' | 'multiplatform'
}

// Platform configurations with custom SVG icons
const platforms: PlatformType[] = [
  {
    id: 'android',
    name: 'Android',
    description: 'Native Android applications',
    icon: ({ className }) => (
      <svg className={className} viewBox="0 0 24 24" fill="currentColor">
        <path d="M17.6 9.48l1.84-3.18c.16-.31.04-.69-.26-.85-.29-.15-.65-.06-.83.22l-1.88 3.24a11.5 11.5 0 0 0-8.94 0L5.65 5.67c-.19-.28-.54-.37-.83-.22-.3.16-.42.54-.26.85l1.84 3.18C4.25 11.24 2.5 13.88 2.5 17h19c0-3.12-1.75-5.76-3.9-7.52M7 15.25c-.69 0-1.25-.56-1.25-1.25s.56-1.25 1.25-1.25 1.25.56 1.25 1.25-.56 1.25-1.25 1.25m10 0c-.69 0-1.25-.56-1.25-1.25s.56-1.25 1.25-1.25 1.25.56 1.25 1.25-.56 1.25-1.25 1.25" transform="scale(1.15) translate(-1.5, -1.5)" />
      </svg>
    ),
    color: '#22c55e',
    category: 'mobile'
  },
  {
    id: 'ios',
    name: 'iOS',
    description: 'Native iOS applications',
    icon: ({ className }) => (
      <svg className={className} viewBox="0 0 24 24" fill="currentColor">
        <path d="M18.71 19.5c-.83 1.24-1.71 2.45-3.05 2.47-1.34.03-1.77-.79-3.29-.79-1.53 0-2 .77-3.27.82-1.31.05-2.3-1.32-3.14-2.53C4.25 17 2.94 12.45 4.7 9.39c.87-1.52 2.43-2.48 4.12-2.51 1.28-.02 2.5.87 3.29.87.78 0 2.26-1.07 3.81-.91.65.03 2.47.26 3.64 1.98-.09.06-2.17 1.28-2.15 3.81.03 3.02 2.65 4.03 2.68 4.04-.03.07-.42 1.44-1.38 2.83M13 3.5c.73-.83 1.94-1.46 2.94-1.5.13 1.17-.34 2.35-1.04 3.19-.69.85-1.83 1.51-2.95 1.42-.15-1.15.41-2.35 1.05-3.11z" />
      </svg>
    ),
    color: '#1f2937',
    category: 'mobile'
  },
  {
    id: 'kmp',
    name: 'Kotlin Multiplatform',
    description: 'Cross-platform with Kotlin',
    icon: ({ className }) => (
      <svg className={className} viewBox="0 0 24 24" fill="currentColor">
        <path d="M2 22L12 12 2 2h10L22 12 12 22H2z" />
      </svg>
    ),
    color: '#9333ea',
    category: 'multiplatform'
  },
  {
    id: 'react-native',
    name: 'React Native',
    description: 'Cross-platform mobile with React',
    icon: ({ className }) => (
      <svg className={className} viewBox="0 0 24 24" fill="currentColor">
        <path d="M12 13.5a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3m6.5-1.5c0-1.78-1.5-3.26-3.54-3.63.35-1.56.35-2.84 0-3.7-.38-.94-1.12-1.42-2.13-1.42-.54 0-1.17.18-1.83.53-1.23.66-2.55 1.87-3.65 3.33C5.96 8.7 5 10.62 5 12.5c0 1.78 1.5 3.26 3.54 3.63-.35 1.56-.35 2.84 0 3.7.38.94 1.12 1.42 2.13 1.42.54 0 1.17-.18 1.83-.53 1.23-.66 2.55-1.87 3.65-3.33 1.39-1.59 2.35-3.51 2.35-5.39M8.8 15.32c-.14.07-.28.13-.4.16.08-.26.17-.54.27-.84.26.24.54.46.83.67-.23.03-.47.04-.7.01m.94-1.9c-.28-.61-.5-1.28-.65-1.98.15-.7.37-1.37.65-1.98.28.61.5 1.28.65 1.98-.15.7-.37 1.37-.65 1.98m-.38-5.22c.14-.07.28-.13.4-.16-.08.26-.17.54-.27.84-.26-.24-.54-.46-.83-.67.23-.03.47-.04.7-.01M12 5.8c.7.46 1.39 1.06 2 1.76-.65-.03-1.3-.03-2-.03-.7 0-1.35 0-2 .03.61-.7 1.3-1.3 2-1.76m3.2 2.72c.14-.07.28-.13.4-.16-.08.26-.17.54-.27.84-.26-.24-.54-.46-.83-.67.23-.03.47-.04.7-.01m.94 1.9c.28.61.5 1.28.65 1.98-.15.7-.37 1.37-.65 1.98-.28-.61-.5-1.28-.65-1.98.15-.7.37-1.37.65-1.98m.38 5.22c-.14.07-.28.13-.4.16.08-.26.17-.54.27-.84.26.24.54.46.83.67-.23.03-.47.04-.7.01M12 19.2c-.7-.46-1.39-1.06-2-1.76.65.03 1.3.03 2 .03.7 0 1.35 0 2-.03-.61.7-1.3 1.3-2 1.76" />
      </svg>
    ),
    color: '#06b6d4',
    category: 'multiplatform'
  },
  {
    id: 'flutter',
    name: 'Flutter',
    description: 'Cross-platform with Dart',
    icon: ({ className }) => (
      <svg className={className} viewBox="0 0 24 24" fill="currentColor">
        <path d="M14.314 0L2.3 12 6 15.7 21.684.013h-7.357m.014 11.072L7.857 17.53l6.47 6.47H21.7l-6.46-6.468 6.46-6.46h-7.37" />
      </svg>
    ),
    color: '#3b82f6',
    category: 'multiplatform'
  },
  {
    id: 'web',
    name: 'Web / JavaScript',
    description: 'Browser-based applications',
    icon: Globe,
    color: '#eab308',
    category: 'web'
  },
  {
    id: 'react',
    name: 'React',
    description: 'React web applications',
    icon: ({ className }) => (
      <svg className={className} viewBox="0 0 24 24" fill="currentColor">
        <path d="M12 10.11c1.03 0 1.87.84 1.87 1.89 0 1-.84 1.85-1.87 1.85S10.13 13 10.13 12c0-1.05.84-1.89 1.87-1.89M7.37 20c.63.38 2.01-.2 3.6-1.7-.52-.59-1.03-1.23-1.51-1.9a22.7 22.7 0 0 1-2.4-.36c-.51 2.14-.32 3.61.31 3.96m.71-5.74l-.29-.51c-.11.29-.22.58-.29.86.27.06.57.11.88.16l-.3-.51m6.54-.76l.81-1.5-.81-1.5c-.3-.53-.62-1-.91-1.47C13.17 9 12.6 9 12 9c-.6 0-1.17 0-1.71.03-.29.47-.61.94-.91 1.47L8.57 12l.81 1.5c.3.53.62 1 .91 1.47.54.03 1.11.03 1.71.03.6 0 1.17 0 1.71-.03.29-.47.61-.94.91-1.47M12 6.78c-.19.22-.39.45-.59.72h1.18c-.2-.27-.4-.5-.59-.72m0 10.44c.19-.22.39-.45.59-.72h-1.18c.2.27.4.5.59.72M16.62 4c-.62-.38-2 .2-3.59 1.7.52.59 1.03 1.23 1.51 1.9.82.08 1.63.2 2.4.36.51-2.14.32-3.61-.32-3.96m-.7 5.74l.29.51c.11-.29.22-.58.29-.86-.27-.06-.57-.11-.88-.16l.3.51m1.45-7.05c1.47.84 1.63 3.05 1.01 5.63 2.54.75 4.37 1.99 4.37 3.68s-1.83 2.93-4.37 3.68c.62 2.58.46 4.79-1.01 5.63-1.46.84-3.45-.12-5.37-1.95-1.92 1.83-3.91 2.79-5.38 1.95-1.46-.84-1.62-3.05-1-5.63-2.54-.75-4.37-1.99-4.37-3.68s1.83-2.93 4.37-3.68c-.62-2.58-.46-4.79 1-5.63 1.47-.84 3.46.12 5.38 1.95 1.92-1.83 3.91-2.79 5.37-1.95M17.08 12c.34.75.64 1.5.89 2.26 2.1-.63 3.28-1.53 3.28-2.26s-1.18-1.63-3.28-2.26c-.25.76-.55 1.51-.89 2.26M6.92 12c-.34-.75-.64-1.5-.89-2.26-2.1.63-3.28 1.53-3.28 2.26s1.18 1.63 3.28 2.26c.25-.76.55-1.51.89-2.26m9 2.26l-.3.51c.31-.05.61-.1.88-.16-.07-.28-.18-.57-.29-.86l-.29.51m-2.89 4.04c1.59 1.5 2.97 2.08 3.59 1.7.64-.35.83-1.82.32-3.96-.77.16-1.58.28-2.4.36-.48.67-.99 1.31-1.51 1.9M8.08 9.74l.3-.51c-.31.05-.61.1-.88.16.07.28.18.57.29.86l.29-.51m2.89-4.04C9.38 4.2 8 3.62 7.37 4c-.63.35-.82 1.82-.31 3.96a22.7 22.7 0 0 1 2.4-.36c.48-.67.99-1.31 1.51-1.9z" />
      </svg>
    ),
    color: '#22d3ee',
    category: 'web'
  },
  {
    id: 'vue',
    name: 'Vue.js',
    description: 'Vue web applications',
    icon: ({ className }) => (
      <svg className={className} viewBox="0 0 24 24" fill="currentColor">
        <path d="M2 3h3.5L12 15l6.5-12H22L12 21z" />
        <path d="M4.5 3L12 15l7.5-12h-3L12 9.5 7.5 3z" />
      </svg>
    ),
    color: '#10b981',
    category: 'web'
  },
  {
    id: 'node',
    name: 'Node.js',
    description: 'Server-side JavaScript',
    icon: Terminal,
    color: '#16a34a',
    category: 'web'
  },
  {
    id: 'python',
    name: 'Python',
    description: 'Python applications',
    icon: ({ className }) => (
      <svg className={className} viewBox="0 0 24 24" fill="currentColor">
        <path d="M14.31.18l.9.2.73.26.59.3.45.32.34.34.25.34.16.33.1.3.04.26.02.2-.01.13V8.5l-.05.63-.13.55-.21.46-.26.38-.3.31-.33.25-.35.19-.35.14-.33.1-.3.07-.26.04-.21.02H8.83l-.69.05-.59.14-.5.22-.41.27-.33.32-.27.35-.2.36-.15.37-.1.35-.07.32-.04.27-.02.21v3.06H3.23l-.21-.03-.28-.07-.32-.12-.35-.18-.36-.26-.36-.36-.35-.46-.32-.59-.28-.73-.21-.88-.14-1.05-.05-1.23.06-1.22.16-1.04.24-.87.32-.71.36-.57.4-.44.42-.33.42-.24.4-.16.36-.1.32-.05.24-.01h.16l.06.01h8.16v-.83H6.24l-.01-2.75-.02-.37.05-.34.11-.31.17-.28.25-.26.31-.23.38-.2.44-.18.51-.15.58-.12.64-.1.71-.06.77-.04.84-.02 1.27.05 1.07.13zm-6.3 1.98l-.23.33-.08.41.08.41.23.34.33.22.41.09.41-.09.33-.22.23-.34.08-.41-.08-.41-.23-.33-.33-.22-.41-.09-.41.09-.33.22z" />
        <path d="M23.24 11.01l-.21.03-.28.07-.32.12-.35.18-.36.26-.36.36-.35.46-.32.59-.28.73-.21.88-.14 1.05-.05 1.23.06 1.22.16 1.04.24.87.32.71.36.57.4.44.42.33.42.24.4.16.36.1.32.05.24.01h.16l.06-.01h8.16v.83h-5.69l.01 2.75.02.37-.05.34-.11.31-.17.28-.25.26-.31.23-.38.2-.44.18-.51.15-.58.12-.64.1-.71.06-.77.04-.84.02-1.27-.05-1.07-.13-.9-.2-.73-.26-.59-.3-.45-.32-.34-.34-.25-.34-.16-.33-.1-.3-.04-.26-.02-.2.01-.13v-5.34l.05-.63.13-.55.21-.46.26-.38.3-.31.33-.25.35-.19.35-.14.33-.1.3-.07.26-.04.21-.02h5.29l.69-.05.59-.14.5-.22.41-.27.33-.32.27-.35.2-.36.15-.37.1-.35.07-.32.04-.27.02-.21V6.07h2.09l.14.01zm-6.47 14.25l-.23.33-.08.41.08.41.23.34.33.22.41.09.41-.09.33-.22.23-.34.08-.41-.08-.41-.23-.33-.33-.22-.41-.09-.41.09-.33.22z" />
      </svg>
    ),
    color: '#2563eb',
    category: 'web'
  },
  {
    id: 'other',
    name: 'Other Platform',
    description: 'Generic project setup',
    icon: Code2,
    color: '#4b5563',
    category: 'multiplatform'
  }
]

export const Route = createFileRoute('/projects')({
  beforeLoad: async () => {
    if (!api.isAuthenticated()) {
      throw redirect({ to: '/login' })
    }
  },
  component: ProjectsPage,
})

function ProjectsPage() {
  const queryClient = useQueryClient()
  const [showCreateProject, setShowCreateProject] = useState(false)
  const [newProjectName, setNewProjectName] = useState('')
  const [selectedPlatform, setSelectedPlatform] = useState<string | null>(null)

  const { data: projects, isLoading } = useQuery({
    queryKey: ['projects'],
    queryFn: () => api.getProjects(),
  })

  const createProjectMutation = useMutation({
    mutationFn: (data: { name: string; platform: string }) => 
      api.createProject(data.name),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['projects'] })
      setShowCreateProject(false)
      setNewProjectName('')
      setSelectedPlatform(null)
    },
  })

  const handleCreateProject = () => {
    if (newProjectName && selectedPlatform) {
      createProjectMutation.mutate({ name: newProjectName, platform: selectedPlatform })
    }
  }

  if (isLoading) return <div className="p-8">Loading...</div>

  return (
    <div className="min-h-screen bg-background">
      <div className="p-6 max-w-7xl mx-auto">
        <div className="mb-6 flex items-center justify-between">
          <h1 className="text-2xl font-bold">Projects</h1>
          <Button onClick={() => setShowCreateProject(true)} size="sm">
            <Plus className="h-4 w-4 mr-2" />
            New Project
          </Button>
        </div>

        {showCreateProject && (
          <Card className="mb-6">
            <CardContent className="pt-6">
              <div className="space-y-4">
                <div>
                  <label className="text-sm font-medium mb-2 block">Project Name</label>
                  <Input
                    placeholder="My awesome app"
                    value={newProjectName}
                    onChange={(e) => setNewProjectName(e.target.value)}
                  />
                </div>

                <div>
                  <label className="text-sm font-medium mb-3 block">Select Platform</label>
                  <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-5 gap-3">
                    {platforms.map((platform) => {
                      const Icon = platform.icon
                      return (
                        <button
                          key={platform.id}
                          onClick={() => setSelectedPlatform(platform.id)}
                          className={`
                            relative flex flex-col items-center gap-2 p-4 rounded-lg border-2 transition-all
                            ${selectedPlatform === platform.id 
                              ? 'border-primary bg-primary/5 shadow-md' 
                              : 'border-border hover:border-primary/50 hover:bg-accent'
                            }
                          `}
                        >
                          <div className={`${platform.color} p-3 rounded-lg`}>
                            <Icon className="h-6 w-6 text-white" />
                          </div>
                          <span className="text-xs font-medium text-center">{platform.name}</span>
                        </button>
                      )
                    })}
                  </div>
                </div>

                <div className="flex gap-2 pt-2">
                  <Button
                    onClick={handleCreateProject}
                    disabled={!newProjectName || !selectedPlatform || createProjectMutation.isPending}
                  >
                    Create Project
                  </Button>
                  <Button variant="outline" onClick={() => {
                    setShowCreateProject(false)
                    setNewProjectName('')
                    setSelectedPlatform(null)
                  }}>
                    Cancel
                  </Button>
                </div>
              </div>
            </CardContent>
          </Card>
        )}

        {!projects || projects.length === 0 ? (
          <Card className="p-12 text-center">
            <div className="max-w-2xl mx-auto space-y-6">
              <div className="flex justify-center">
                <div className="rounded-full bg-primary/10 p-4">
                  <Smartphone className="h-10 w-10 text-primary" />
                </div>
              </div>
              <div>
                <h3 className="text-lg font-semibold mb-2">No projects yet</h3>
                <p className="text-muted-foreground mb-6">
                  Create your first project to start tracking errors and monitoring your applications.
                </p>
              </div>
              
              <div>
                <h4 className="text-sm font-medium mb-4">Get started with any platform:</h4>
                <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-5 gap-3 max-w-4xl mx-auto">
                  {platforms.slice(0, 10).map((platform) => {
                    const Icon = platform.icon
                    return (
                      <div
                        key={platform.id}
                        className="flex flex-col items-center gap-2 p-3 rounded-lg border bg-card hover:bg-accent transition-colors"
                      >
                        <div className={`${platform.color} p-2.5 rounded-lg`}>
                          <Icon className="h-5 w-5 text-white" />
                        </div>
                        <span className="text-xs font-medium text-center">{platform.name}</span>
                      </div>
                    )
                  })}
                </div>
              </div>

              <Button onClick={() => setShowCreateProject(true)} size="lg" className="mt-4">
                <Plus className="h-4 w-4 mr-2" />
                Create Your First Project
              </Button>
            </div>
          </Card>
        ) : (
          <div className="space-y-3">
            {projects.map((project) => (
              <Link
                key={project.id}
                to="/"
                className="block"
              >
                <Card className="p-4 hover:bg-accent transition-colors cursor-pointer">
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-4">
                      <div 
                        className="p-3 rounded-lg flex items-center justify-center w-12 h-12 flex-shrink-0"
                        style={{ backgroundColor: getProjectColor(project.name) }}
                      >
                        <span className="text-white font-semibold text-lg">{getProjectInitial(project.name)}</span>
                      </div>
                      <div>
                        <div className="flex items-center gap-2">
                          <h3 className="font-semibold">{project.name}</h3>
                          {project.platform && (() => {
                            const platformInfo = getPlatformInfo(project.platform)
                            if (!platformInfo) return null
                            const PlatformIcon = platformInfo.icon
                            return (
                              <Badge 
                                className="flex items-center gap-1.5 px-2 py-0.5 text-white border-0"
                                style={{ backgroundColor: platformInfo.color }}
                              >
                                <div className="w-3.5 h-3.5 flex items-center justify-center">
                                  <PlatformIcon className="w-full h-full" />
                                </div>
                                <span className="text-xs font-medium">{platformInfo.name}</span>
                              </Badge>
                            )
                          })()}
                        </div>
                        <p className="text-sm text-muted-foreground">
                          Created {formatRelativeTime(project.createdAt)}
                        </p>
                      </div>
                    </div>
                    <div className="flex items-center gap-4">
                      <div className="text-right">
                        <div className="text-sm text-muted-foreground">DSN</div>
                        <code className="text-xs bg-muted px-2 py-1 rounded">{project.dsn.slice(0, 20)}...</code>
                      </div>
                      <Button variant="ghost" size="icon">
                        <Settings className="h-4 w-4" />
                      </Button>
                    </div>
                  </div>
                </Card>
              </Link>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
