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

export interface Event {
  eventId: string
  timestamp: string
  message: string
  platform: string
  level: string
  environment?: string
  release?: string
  user?: {
    id: string
    email?: string
    username?: string
  }
  tags: Record<string, string>
  contexts: string
  exception?: string
  breadcrumbs?: string
}

export interface Issue {
  id: string
  projectId: number
  projectResourceId: string
  title: string
  culprit: string
  level: string
  platform: string
  firstSeen: string
  lastSeen: string
  eventCount: number
  userCount: number
  status: string
  substatus?: string
  statusDetail?: Record<string, string>
}

export interface IssueListParams {
  page?: number
  limit?: number
  status?: string
  services?: string[]
  serviceIds?: Array<string | number>
}

export interface IssueDetail extends Issue {
  projectName?: string
  fingerprint: string[]
  latestEvent?: Event
}

export interface TimelinePoint {
  timestamp: string
  count: number
}

export interface TopIssue {
  issueId: string
  title: string
  count: number
}
