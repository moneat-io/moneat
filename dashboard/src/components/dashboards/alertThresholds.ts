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

import type {DashboardAlertCondition} from '@/lib/api'

export interface AlertThresholdPreview {
  condition: DashboardAlertCondition
  warningThreshold: number | null
  errorThreshold: number
}

export function isWarningThresholdValid({
  condition,
  warningThreshold,
  errorThreshold,
}: AlertThresholdPreview): boolean {
  if (warningThreshold == null) return true
  if (condition === '==') return warningThreshold === errorThreshold
  if (condition === '>' || condition === '>=') return warningThreshold < errorThreshold
  return warningThreshold > errorThreshold
}

export function getWarningThresholdMessage(condition: DashboardAlertCondition): string {
  if (condition === '>' || condition === '>=') {
    return 'Warning threshold must be below the error threshold.'
  }
  if (condition === '<' || condition === '<=') {
    return 'Warning threshold must be above the error threshold.'
  }
  return 'Warning threshold must match the error threshold.'
}
