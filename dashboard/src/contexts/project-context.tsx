import { createContext, useContext, useState, useEffect, ReactNode } from 'react'

interface ProjectContextType {
  selectedProjectId: number | null
  setSelectedProjectId: (id: number | null) => void
}

const ProjectContext = createContext<ProjectContextType | undefined>(undefined)

export function ProjectProvider({ children }: { children: ReactNode }) {
  const [selectedProjectId, setSelectedProjectId] = useState<number | null>(() => {
    const saved = localStorage.getItem('selectedProjectId')
    return saved ? Number(saved) : null
  })

  useEffect(() => {
    if (selectedProjectId !== null) {
      localStorage.setItem('selectedProjectId', selectedProjectId.toString())
    } else {
      localStorage.removeItem('selectedProjectId')
    }
  }, [selectedProjectId])

  return (
    <ProjectContext.Provider value={{ selectedProjectId, setSelectedProjectId }}>
      {children}
    </ProjectContext.Provider>
  )
}

export function useProject() {
  const context = useContext(ProjectContext)
  if (context === undefined) {
    throw new Error('useProject must be used within a ProjectProvider')
  }
  return context
}
