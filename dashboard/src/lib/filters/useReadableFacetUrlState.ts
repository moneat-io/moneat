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

import {useNavigate} from '@tanstack/react-router'
import {useEffect, useMemo, useRef, useState, type Dispatch, type SetStateAction} from 'react'

import type {FacetFilter} from '@/lib/filters/types'
import {
  EXCLUDE_FACET_PARAM_PREFIX,
  LEGACY_FACETS_PARAM,
  parseReadableFacetFilters,
  serializeReadableFacetFilters,
} from '@/lib/filters/urlState'

const EMPTY_FACET_FILTERS: readonly FacetFilter[] = []

export interface ReadableFacetUrlState {
  query: string
  setQuery: Dispatch<SetStateAction<string>>
  facetFilters: FacetFilter[]
  setFacetFilters: Dispatch<SetStateAction<FacetFilter[]>>
}

export interface ReadableFacetUrlStateOptions {
  facetKeys: readonly string[]
  defaultQuery?: string
  defaultFacetFilters?: readonly FacetFilter[]
  syncQuery?: boolean
}

function searchParamsToRecord(params: URLSearchParams): Record<string, unknown> {
  const record: Record<string, string | string[]> = {}
  for (const [key, value] of params.entries()) {
    const current = record[key]
    if (Array.isArray(current)) {
      current.push(value)
    } else if (current !== undefined) {
      record[key] = [current, value]
    } else {
      record[key] = value
    }
  }
  return record
}

function readUrlState({
  facetKeys,
  defaultQuery = '',
  defaultFacetFilters = EMPTY_FACET_FILTERS,
  syncQuery = true,
}: ReadableFacetUrlStateOptions): Pick<ReadableFacetUrlState, 'query' | 'facetFilters'> {
  if (typeof globalThis.window === 'undefined') {
    return {query: defaultQuery, facetFilters: [...defaultFacetFilters]}
  }

  const params = new URLSearchParams(globalThis.window.location.search)
  const search = searchParamsToRecord(params)
  const queryValue = syncQuery ? params.get('q')?.trim() || defaultQuery : defaultQuery
  const parsedFacets = parseReadableFacetFilters(search, facetKeys)

  return {
    query: queryValue,
    facetFilters: parsedFacets ?? [...defaultFacetFilters],
  }
}

function readableFacetSearch(
  search: Record<string, unknown>,
  query: string,
  facetFilters: readonly FacetFilter[],
  {facetKeys, syncQuery = true}: ReadableFacetUrlStateOptions
): Record<string, unknown> {
  const next = {...search}
  for (const key of facetKeys) {
    next[key] = undefined
    next[`${EXCLUDE_FACET_PARAM_PREFIX}${key}`] = undefined
  }
  next[LEGACY_FACETS_PARAM] = undefined

  if (syncQuery) {
    const trimmedQuery = query.trim()
    next.q = trimmedQuery || undefined
  }

  return {
    ...next,
    ...serializeReadableFacetFilters(facetFilters, facetKeys),
  }
}

function readableFacetUrlMatchesCurrentSearch(
  query: string,
  facetFilters: readonly FacetFilter[],
  {facetKeys, syncQuery = true}: ReadableFacetUrlStateOptions
): boolean {
  if (typeof globalThis.window === 'undefined') return true

  const url = new URL(globalThis.window.location.href)
  for (const key of facetKeys) {
    url.searchParams.delete(key)
    url.searchParams.delete(`${EXCLUDE_FACET_PARAM_PREFIX}${key}`)
  }
  url.searchParams.delete(LEGACY_FACETS_PARAM)

  if (syncQuery) {
    const trimmedQuery = query.trim()
    if (trimmedQuery) {
      url.searchParams.set('q', trimmedQuery)
    } else {
      url.searchParams.delete('q')
    }
  }

  const nextFacets = serializeReadableFacetFilters(facetFilters, facetKeys)
  for (const [key, value] of Object.entries(nextFacets)) {
    if (Array.isArray(value)) {
      for (const item of value) {
        url.searchParams.append(key, item)
      }
    } else {
      url.searchParams.set(key, value)
    }
  }

  const nextUrl = `${url.pathname}${url.search}${url.hash}`
  const currentUrl = [
    globalThis.window.location.pathname,
    globalThis.window.location.search,
    globalThis.window.location.hash,
  ].join('')

  if (nextUrl !== currentUrl) {
    return false
  }
  return true
}

export function useReadableFacetUrlState({
  facetKeys,
  defaultQuery = '',
  defaultFacetFilters = EMPTY_FACET_FILTERS,
  syncQuery = true,
}: ReadableFacetUrlStateOptions): ReadableFacetUrlState {
  const navigate = useNavigate()
  const options = useMemo(
    () => ({facetKeys, defaultQuery, defaultFacetFilters, syncQuery}),
    [defaultFacetFilters, defaultQuery, facetKeys, syncQuery]
  )
  const [query, setQuery] = useState(() => readUrlState(options).query)
  const [facetFilters, setFacetFilters] = useState<FacetFilter[]>(() => readUrlState(options).facetFilters)
  const applyingUrlStateRef = useRef(false)

  useEffect(() => {
    if (typeof globalThis.window === 'undefined') return undefined

    const handlePopState = () => {
      const nextState = readUrlState(options)
      applyingUrlStateRef.current = true
      setQuery(nextState.query)
      setFacetFilters(nextState.facetFilters)
    }

    globalThis.window.addEventListener('popstate', handlePopState)
    return () => globalThis.window.removeEventListener('popstate', handlePopState)
  }, [options])

  useEffect(() => {
    if (applyingUrlStateRef.current) {
      applyingUrlStateRef.current = false
      return
    }
    if (readableFacetUrlMatchesCurrentSearch(query, facetFilters, options)) return
    navigate({
      replace: true,
      search: ((prev: Record<string, unknown>) =>
        readableFacetSearch(prev, query, facetFilters, options)) as never,
    })
  }, [facetFilters, navigate, options, query])

  return {query, setQuery, facetFilters, setFacetFilters}
}
