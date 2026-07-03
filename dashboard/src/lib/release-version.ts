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

type ParsedReleaseVersion = {
  family: string
  core: number[]
  prerelease: string[]
  build: string[]
}

const VERSION_CANDIDATE_PATTERN = /v?\d+(?:\.\d+)+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?/g

export function compareReleaseVersionPrecedence(left: string, right: string): number {
  const leftVersion = parseReleaseVersion(left)
  const rightVersion = parseReleaseVersion(right)

  if (leftVersion == null || rightVersion == null) return 0
  if (leftVersion.family !== rightVersion.family) return 0

  const coreOrder = compareNumberList(leftVersion.core, rightVersion.core)
  if (coreOrder !== 0) return coreOrder

  const prereleaseOrder = comparePrerelease(leftVersion.prerelease, rightVersion.prerelease)
  if (prereleaseOrder !== 0) return prereleaseOrder

  return compareBuild(leftVersion.build, rightVersion.build)
}

function parseReleaseVersion(value: string): ParsedReleaseVersion | null {
  const trimmed = value.trim()
  const atIndex = trimmed.lastIndexOf('@')
  if (atIndex >= 0) {
    const atVersion = parseCandidateAt(trimmed, atIndex + 1)
    if (atVersion) return atVersion
  }

  const matches = Array.from(trimmed.matchAll(VERSION_CANDIDATE_PATTERN))
  const match = matches.at(-1)
  if (match?.index == null) return null

  return parseCandidate(trimmed, match.index, match[0])
}

function parseCandidateAt(value: string, index: number): ParsedReleaseVersion | null {
  const match = /^v?\d+(?:\.\d+)+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?/.exec(value.slice(index))
  if (!match) return null

  return parseCandidate(value, index, match[0])
}

function parseCandidate(source: string, index: number, candidate: string): ParsedReleaseVersion | null {
  const normalized = candidate.replace(/^v/i, '')
  const buildIndex = normalized.indexOf('+')
  const withoutBuild = buildIndex >= 0 ? normalized.slice(0, buildIndex) : normalized
  const build = buildIndex >= 0 ? normalized.slice(buildIndex + 1).split('.') : []
  const prereleaseIndex = withoutBuild.indexOf('-')
  const coreText = prereleaseIndex >= 0 ? withoutBuild.slice(0, prereleaseIndex) : withoutBuild
  const prerelease = prereleaseIndex >= 0 ? withoutBuild.slice(prereleaseIndex + 1).split('.') : []
  const core = coreText.split('.').map((segment) => Number.parseInt(segment, 10))

  if (core.some((segment) => !Number.isSafeInteger(segment))) return null

  return {
    family: source.slice(0, index).toLowerCase(),
    core,
    prerelease,
    build,
  }
}

function compareNumberList(left: number[], right: number[]): number {
  const segmentCount = Math.max(left.length, right.length)
  for (let index = 0; index < segmentCount; index += 1) {
    const leftSegment = left[index] ?? 0
    const rightSegment = right[index] ?? 0
    if (leftSegment !== rightSegment) return leftSegment - rightSegment
  }

  return 0
}

function comparePrerelease(left: string[], right: string[]): number {
  if (left.length === 0 && right.length === 0) return 0
  if (left.length === 0) return 1
  if (right.length === 0) return -1

  return compareIdentifierList(left, right)
}

function compareBuild(left: string[], right: string[]): number {
  if (left.length === 0 && right.length === 0) return 0
  if (left.length === 0) return -1
  if (right.length === 0) return 1

  return compareIdentifierList(left, right)
}

function compareIdentifierList(left: string[], right: string[]): number {
  const segmentCount = Math.max(left.length, right.length)
  for (let index = 0; index < segmentCount; index += 1) {
    const leftSegment = left[index]
    const rightSegment = right[index]
    if (leftSegment == null) return -1
    if (rightSegment == null) return 1

    const segmentOrder = compareIdentifier(leftSegment, rightSegment)
    if (segmentOrder !== 0) return segmentOrder
  }

  return 0
}

function compareIdentifier(left: string, right: string): number {
  const leftNumber = parseNumericIdentifier(left)
  const rightNumber = parseNumericIdentifier(right)

  if (leftNumber != null && rightNumber != null) return leftNumber - rightNumber
  if (leftNumber != null) return -1
  if (rightNumber != null) return 1

  return left.localeCompare(right)
}

function parseNumericIdentifier(value: string): number | null {
  if (!/^\d+$/.test(value)) return null

  const parsed = Number.parseInt(value, 10)
  return Number.isSafeInteger(parsed) ? parsed : null
}
