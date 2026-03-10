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

package com.moneat.testsupport

import com.moneat.di.appModules
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

/**
 * Starts Koin with the full app modules if not already running.
 * Safe to call multiple times — subsequent calls are no-ops.
 */
fun startTestKoin() {
    if (GlobalContext.getOrNull() == null) {
        startKoin { modules(appModules) }
    }
}

/**
 * Stops Koin if it is currently running.
 * Safe to call even when Koin is not started.
 */
fun stopTestKoin() {
    if (GlobalContext.getOrNull() != null) {
        stopKoin()
    }
}
