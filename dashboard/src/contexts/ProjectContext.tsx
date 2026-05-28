// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

import {createContext, ReactNode, useContext, useEffect, useMemo, useState} from 'react'

interface ProjectContextType {
  readonly selectedProjectId: string | null
  readonly setSelectedProjectId: (id: string | null) => void
}

const ProjectContext = createContext<ProjectContextType | undefined>(undefined)

export function ProjectProvider({ children }: { readonly children: ReactNode }) {
  const [selectedProjectId, setSelectedProjectId] = useState<string | null>(() => {
    const saved = localStorage.getItem('selectedProjectId')
    return saved || null
  })

  useEffect(() => {
    if (selectedProjectId === null) {
      localStorage.removeItem('selectedProjectId')
    } else {
      localStorage.setItem('selectedProjectId', selectedProjectId)
    }
  }, [selectedProjectId])

  const contextValue = useMemo(
    () => ({ selectedProjectId, setSelectedProjectId }),
    [selectedProjectId, setSelectedProjectId],
  )

  return (
    <ProjectContext.Provider value={contextValue}>
      {children}
    </ProjectContext.Provider>
  )
}

// eslint-disable-next-line react-refresh/only-export-components
export function useProject() {
  const context = useContext(ProjectContext)
  if (context === undefined) {
    throw new Error('useProject must be used within a ProjectProvider')
  }
  return context
}
