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

export const EXTENDED_WIDGET_TYPES = new Set([
  'stream',
  'timeline',
  'geo_map',
  'host_map',
  'topology_map',
  'sankey',
  'treemap',
  'scatter',
  'status',
  'change',
  'custom',
  'flame_graph',
  'cost_summary',
  'iframe',
])

export function isExtendedWidgetType(widgetType: string): boolean {
  return EXTENDED_WIDGET_TYPES.has(widgetType)
}

export function isQueryDrivenExtendedWidget(widgetType: string): boolean {
  return widgetType !== 'iframe'
}

export function extendedWidgetTestId(widgetType: string): string {
  return `widget-${widgetType}`
}
