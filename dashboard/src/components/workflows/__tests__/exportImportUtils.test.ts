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

import {describe, expect, it} from 'vitest'
import type {WorkflowExportResponse} from '@/lib/api'
import {jsonExport, parseImportJson} from '../exportImportUtils'

const graph = {nodes: [], edges: []}

describe('jsonExport', () => {
  it('serialises the export resource', () => {
    const exportData: WorkflowExportResponse = {
      schema_version: 1,
      resource: {name: 'Pager', trigger_name: 'issue.created', enabled: true, graph, once_for_template: []},
      terraform: 'resource {}',
    }
    expect(jsonExport(exportData)).toContain('"name": "Pager"')
  })
})

describe('parseImportJson', () => {
  it('returns an error for invalid JSON', () => {
    expect(parseImportJson('{not json')).toBe('Invalid JSON.')
  })

  it('rejects non-object JSON', () => {
    expect(parseImportJson('[1,2]')).toBe('Expected a JSON object.')
    expect(parseImportJson('42')).toBe('Expected a JSON object.')
  })

  it('requires a name, trigger, and graph', () => {
    expect(parseImportJson(JSON.stringify({trigger_name: 't', graph}))).toBe(
      'A workflow name is required.'
    )
    expect(parseImportJson(JSON.stringify({name: 'n', graph}))).toBe(
      'A trigger name is required.'
    )
    expect(parseImportJson(JSON.stringify({name: 'n', trigger_name: 't'}))).toBe(
      'A graph with nodes and edges arrays is required.'
    )
  })

  it('rejects a graph missing nodes or edges arrays', () => {
    expect(parseImportJson(JSON.stringify({name: 'n', trigger_name: 't', graph: {}}))).toBe(
      'A graph with nodes and edges arrays is required.'
    )
    expect(parseImportJson(JSON.stringify({name: 'n', trigger_name: 't', graph: {nodes: []}}))).toBe(
      'A graph with nodes and edges arrays is required.'
    )
  })

  it('parses a raw resource into a request', () => {
    const result = parseImportJson(
      JSON.stringify({
        name: 'Imported',
        trigger_name: 'issue.created',
        graph,
        enabled: false,
        once_for_template: ['{{issue.id}}', 5],
      })
    )
    expect(result).toEqual({
      name: 'Imported',
      trigger_name: 'issue.created',
      graph,
      enabled: false,
      once_for_template: ['{{issue.id}}'],
    })
  })

  it('unwraps a full export envelope', () => {
    const result = parseImportJson(
      JSON.stringify({
        schema_version: 1,
        resource: {name: 'Env', trigger_name: 'issue.created', graph},
        terraform: 'resource {}',
      })
    )
    expect(result).toEqual({name: 'Env', trigger_name: 'issue.created', graph})
  })
})
