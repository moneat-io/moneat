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

export const MAX_QUERY_RETRIES = 3

/**
 * Default retry predicate for React Query.
 *
 * Client errors (4xx) — notably 404 Not Found — are not transient and will
 * never succeed on retry, so we surface them immediately instead of hammering
 * the backend. Errors without a status (e.g. network failures) and server
 * errors (5xx) are retried up to {@link MAX_QUERY_RETRIES} times.
 */
export function shouldRetryQuery(failureCount: number, error: unknown): boolean {
  const status = (error as {status?: number} | null | undefined)?.status
  if (typeof status === 'number' && status >= 400 && status < 500) {
    return false
  }
  return failureCount < MAX_QUERY_RETRIES
}
