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
import {getWarningThresholdMessage, isWarningThresholdValid} from '../alertThresholds'
import type {DashboardAlertCondition} from '@/lib/api'

describe('alertThresholds', () => {
  describe('isWarningThresholdValid', () => {
    it('allows alerts without warning thresholds', () => {
      expect(isWarningThresholdValid({
        condition: '>',
        warningThreshold: null,
        errorThreshold: 100,
      })).toBe(true)
    })

    it('requires equality alerts to share the error threshold', () => {
      expect(isWarningThresholdValid({
        condition: '==',
        warningThreshold: 100,
        errorThreshold: 100,
      })).toBe(true)
      expect(isWarningThresholdValid({
        condition: '==',
        warningThreshold: 90,
        errorThreshold: 100,
      })).toBe(false)
    })

    it.each<DashboardAlertCondition>(['>', '>='])(
      'requires warning below error for %s alerts',
      (condition) => {
        expect(isWarningThresholdValid({
          condition,
          warningThreshold: 75,
          errorThreshold: 100,
        })).toBe(true)
        expect(isWarningThresholdValid({
          condition,
          warningThreshold: 125,
          errorThreshold: 100,
        })).toBe(false)
      }
    )

    it.each<DashboardAlertCondition>(['<', '<='])(
      'requires warning above error for %s alerts',
      (condition) => {
        expect(isWarningThresholdValid({
          condition,
          warningThreshold: 125,
          errorThreshold: 100,
        })).toBe(true)
        expect(isWarningThresholdValid({
          condition,
          warningThreshold: 75,
          errorThreshold: 100,
        })).toBe(false)
      }
    )
  })

  describe('getWarningThresholdMessage', () => {
    it.each([
      ['>', 'Warning threshold must be below the error threshold.'],
      ['>=', 'Warning threshold must be below the error threshold.'],
      ['<', 'Warning threshold must be above the error threshold.'],
      ['<=', 'Warning threshold must be above the error threshold.'],
      ['==', 'Warning threshold must match the error threshold.'],
    ] as const)('returns validation copy for %s alerts', (condition, message) => {
      expect(getWarningThresholdMessage(condition)).toBe(message)
    })
  })
})
