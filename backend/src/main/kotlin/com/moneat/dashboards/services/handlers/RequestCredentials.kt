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

package com.moneat.dashboards.services.handlers

import com.moneat.dashboards.models.TestConnectionRequest
import com.moneat.dashboards.services.DataSourceCredentials

/**
 * Maps a (non-persisted) test-connection request to the same credential shape the
 * executor passes to handlers, so the test path and the query path share one auth +
 * options code path. Parsed connection options ride in [DataSourceCredentials.options].
 */
internal fun TestConnectionRequest.toCredentials(): DataSourceCredentials = DataSourceCredentials(
    username = username,
    password = password,
    apiKey = apiKey,
    headerName = extraConfig["header_name"],
    headerValue = headerValue,
    accessKeyId = accessKeyId,
    secretAccessKey = secretAccessKey,
    serviceAccountJson = serviceAccountJson,
    accountIdentifier = accountIdentifier,
    connectionString = connectionString,
    projectId = projectId,
    region = region,
    options = ConnectionOptions.from(extraConfig),
)

/**
 * Returns a copy of these decrypted credentials carrying the connection options
 * parsed from a data source's extra_config, so the executor and handlers read auth
 * method, TLS mode, org/bucket, etc. from a single carrier.
 */
internal fun DataSourceCredentials.withConnectionOptions(extraConfig: Map<String, String>): DataSourceCredentials =
    copy(options = ConnectionOptions.from(extraConfig))
