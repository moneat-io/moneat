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

package com.moneat.analytics.services

import mu.KotlinLogging
import java.io.File
import java.net.InetAddress

private val logger = KotlinLogging.logger {}

/**
 * Resolves IP addresses to geographic locations using MaxMind GeoLite2-City.
 * If the GeoIP database is unavailable, returns empty location data gracefully.
 *
 * IP addresses are NEVER stored — only the resolved country/subdivision/city are kept.
 */
class GeoIpService {

    data class GeoResult(
        val countryCode: String = "",
        val subdivision: String = "",
        val city: String = "",
    )

    // MaxMind reader, initialized lazily if database file is available
    private val reader: Any? by lazy { initReader() }
    private var readerAvailable = false

    @Suppress("TooGenericExceptionCaught")
    private fun initReader(): Any? {
        val dbPath = System.getenv("GEOIP_DB_PATH")
            ?: "/usr/share/GeoIP/GeoLite2-City.mmdb"
        val dbFile = File(dbPath)
        if (!dbFile.exists()) {
            logger.warn { "GeoIP database not found at $dbPath — location data will be empty" }
            return null
        }
        return try {
            // Use reflection to avoid hard compile-time dependency on MaxMind
            val dbReaderClass = Class.forName("com.maxmind.geoip2.DatabaseReader\$Builder")
            val builder = dbReaderClass.getConstructor(File::class.java).newInstance(dbFile)
            val buildMethod = dbReaderClass.getMethod("build")
            val instance = buildMethod.invoke(builder)
            readerAvailable = true
            logger.info { "GeoIP database loaded from $dbPath" }
            instance
        } catch (e: Exception) {
            logger.warn(e) { "Failed to initialize GeoIP reader — location data will be empty" }
            null
        }
    }

    /**
     * Resolve an IP address to geographic location.
     * Returns empty GeoResult if GeoIP is unavailable or IP can't be resolved.
     */
    @Suppress("TooGenericExceptionCaught")
    fun resolve(ip: String): GeoResult {
        if (!readerAvailable || reader == null) return GeoResult()
        return try {
            val address = InetAddress.getByName(ip)
            val cityMethod = reader!!.javaClass.getMethod("city", InetAddress::class.java)
            val response = cityMethod.invoke(reader, address)

            val countryObj = response.javaClass.getMethod("getCountry").invoke(response)
            val countryCode = countryObj?.javaClass?.getMethod("getIsoCode")?.invoke(countryObj)?.toString() ?: ""

            val subdivisions = response.javaClass.getMethod("getMostSpecificSubdivision").invoke(response)
            val subdivision = subdivisions?.javaClass?.getMethod("getName")?.invoke(subdivisions)?.toString() ?: ""

            val cityObj = response.javaClass.getMethod("getCity").invoke(response)
            val city = cityObj?.javaClass?.getMethod("getName")?.invoke(cityObj)?.toString() ?: ""

            GeoResult(countryCode, subdivision, city)
        } catch (e: Exception) {
            logger.debug { "GeoIP lookup failed for $ip: ${e.message}" }
            GeoResult()
        }
    }
}
