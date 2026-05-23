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

package com.moneat.billing.services

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.plus

internal data class BillingPeriod(
    val start: LocalDate,
    val end: LocalDate,
)

private const val MIN_DAY_OF_MONTH = 1
private const val LAST_UNIVERSAL_MONTH_DAY = 28
private const val MAX_DAY_OF_MONTH = 31
private const val MONTHS_PER_YEAR = 12
private const val MONTHS_PER_MONTH = 1

internal fun resolveCurrentBillingPeriod(
    storedStart: LocalDate?,
    storedEnd: LocalDate?,
    billingInterval: String?,
    today: LocalDate,
): BillingPeriod {
    if (storedStart == null) {
        val start = LocalDate(today.year, today.month, MIN_DAY_OF_MONTH)
        return BillingPeriod(start = start, end = endForPeriodStart(start, MONTHS_PER_MONTH, MIN_DAY_OF_MONTH))
    }

    val monthsPerPeriod = monthsPerBillingPeriod(billingInterval)
    val anchorDay = storedStart.day
    val normalizedStoredEnd =
        storedEnd
            ?.takeIf { it >= storedStart }
            ?: endForPeriodStart(storedStart, monthsPerPeriod, anchorDay)

    if (today >= storedStart && today <= normalizedStoredEnd) {
        return BillingPeriod(start = storedStart, end = normalizedStoredEnd)
    }

    return if (today > normalizedStoredEnd) {
        advancePeriodUntilCurrent(storedStart, today, monthsPerPeriod, anchorDay)
    } else {
        rewindPeriodUntilCurrent(storedStart, today, monthsPerPeriod, anchorDay)
    }
}

private fun advancePeriodUntilCurrent(
    storedStart: LocalDate,
    today: LocalDate,
    monthsPerPeriod: Int,
    anchorDay: Int,
): BillingPeriod {
    var periodStart = storedStart
    var periodEnd = endForPeriodStart(periodStart, monthsPerPeriod, anchorDay)

    while (today > periodEnd) {
        periodStart = shiftBillingAnchor(periodStart, monthsPerPeriod, anchorDay)
        periodEnd = endForPeriodStart(periodStart, monthsPerPeriod, anchorDay)
    }

    return BillingPeriod(start = periodStart, end = periodEnd)
}

private fun rewindPeriodUntilCurrent(
    storedStart: LocalDate,
    today: LocalDate,
    monthsPerPeriod: Int,
    anchorDay: Int,
): BillingPeriod {
    var periodStart = storedStart

    while (today < periodStart) {
        periodStart = shiftBillingAnchor(periodStart, -monthsPerPeriod, anchorDay)
    }

    return BillingPeriod(start = periodStart, end = endForPeriodStart(periodStart, monthsPerPeriod, anchorDay))
}

private fun endForPeriodStart(
    periodStart: LocalDate,
    monthsPerPeriod: Int,
    anchorDay: Int,
): LocalDate {
    return shiftBillingAnchor(periodStart, monthsPerPeriod, anchorDay).plus(DatePeriod(days = -1))
}

private fun shiftBillingAnchor(
    date: LocalDate,
    months: Int,
    anchorDay: Int,
): LocalDate {
    val targetMonth = LocalDate(date.year, date.month, MIN_DAY_OF_MONTH).plus(DatePeriod(months = months))
    return dateWithClampedDay(targetMonth.year, targetMonth.month, anchorDay)
}

private fun dateWithClampedDay(
    year: Int,
    month: Month,
    anchorDay: Int,
): LocalDate {
    var day = anchorDay.coerceIn(MIN_DAY_OF_MONTH, MAX_DAY_OF_MONTH)
    while (day > LAST_UNIVERSAL_MONTH_DAY) {
        try {
            return LocalDate(year, month, day)
        } catch (_: IllegalArgumentException) {
            day--
        }
    }
    return LocalDate(year, month, day)
}

private fun monthsPerBillingPeriod(billingInterval: String?): Int {
    return when (billingInterval?.lowercase()) {
        "annual", "annually", "year", "yearly" -> MONTHS_PER_YEAR
        else -> MONTHS_PER_MONTH
    }
}
