// Moneat - Mobile-First Error Monitoring Platform
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

package com.moneat.utils

import io.sentry.*
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

object SentryUtils {
    /**
     * Execute a block within a Sentry span, automatically finishing it when complete
     */
    suspend fun <T> withSpan(
        parent: ISpan?,
        operation: String,
        description: String? = null,
        block: suspend (ISpan?) -> T
    ): T {
        if (parent == null || !Sentry.isEnabled()) {
            return block(null)
        }
        
        val span = parent.startChild(operation, description)
        return try {
            block(span).also {
                span.status = SpanStatus.OK
            }
        } catch (e: Exception) {
            span.status = SpanStatus.INTERNAL_ERROR
            span.throwable = e
            throw e
        } finally {
            span.finish()
        }
    }
    
    /**
     * Execute a block within a Sentry transaction, automatically finishing it when complete
     */
    suspend fun <T> withTransaction(
        name: String,
        operation: String,
        block: suspend (ITransaction?) -> T
    ): T {
        if (!Sentry.isEnabled()) {
            return block(null)
        }
        
        val transaction = Sentry.startTransaction(name, operation)
        return try {
            block(transaction).also {
                transaction.status = SpanStatus.OK
            }
        } catch (e: Exception) {
            transaction.status = SpanStatus.INTERNAL_ERROR
            transaction.throwable = e
            throw e
        } finally {
            transaction.finish()
        }
    }
    
    /**
     * Add a breadcrumb to Sentry for debugging
     */
    fun addBreadcrumb(
        message: String,
        category: String,
        level: SentryLevel = SentryLevel.INFO,
        data: Map<String, Any>? = null
    ) {
        if (!Sentry.isEnabled()) return
        
        Sentry.addBreadcrumb(Breadcrumb().apply {
            this.message = message
            this.category = category
            this.level = level
            data?.forEach { (key, value) ->
                // Convert to string to avoid serialization issues with complex objects
                // This ensures we never try to serialize Stripe SDK objects or LinkedHashMaps
                val stringValue = when (value) {
                    is String -> value
                    is Number -> value.toString()
                    is Boolean -> value.toString()
                    else -> value.toString() // Safe fallback for any complex object
                }
                this.setData(key, stringValue)
            }
        })
    }
    
    /**
     * Add a simple breadcrumb for a state change or event
     */
    fun breadcrumb(category: String, message: String, data: Map<String, Any>? = null) {
        addBreadcrumb(message, category, SentryLevel.INFO, data)
    }
    
    /**
     * Set user context in Sentry
     */
    fun setUser(userId: Int, email: String? = null, username: String? = null) {
        if (!Sentry.isEnabled()) return
        
        Sentry.setUser(io.sentry.protocol.User().apply {
            this.id = userId.toString()
            this.email = email
            this.username = username
        })
    }
    
    /**
     * Clear user context
     */
    fun clearUser() {
        if (!Sentry.isEnabled()) return
        Sentry.setUser(null)
    }
    
    /**
     * Set a tag in the current scope
     */
    fun setTag(key: String, value: String) {
        if (!Sentry.isEnabled()) return
        Sentry.setTag(key, value)
    }
    
    /**
     * Set extra context data
     */
    fun setExtra(key: String, value: Any) {
        if (!Sentry.isEnabled()) return
        Sentry.setExtra(key, value.toString())
    }
}
