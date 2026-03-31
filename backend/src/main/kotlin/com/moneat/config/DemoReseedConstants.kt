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

package com.moneat.config

// Demo project IDs as UInt64 values matching the negative-ID convention
internal const val P1 = "toUInt64(-1)"
internal const val P2 = "toUInt64(-2)"
internal const val P3 = "toUInt64(-3)"

// Log reseed tuning
internal const val LOG_SEED_ROWS = 300
internal const val LOG_BUCKET_1_MAX = 80
internal const val LOG_BUCKET_2_MAX = 160
internal const val LOG_BUCKET_3_MAX = 240
internal const val LOG_BUCKET_4_BASE_MINUTES = 60

internal const val DEMO_PROFILE_COUNT = 15

/** Demo org id for Datadog / infra-style ClickHouse tables (UInt64). */
internal const val ORG1 = "toUInt64(-1)"
