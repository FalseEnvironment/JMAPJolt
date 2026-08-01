package com.falseenvironment.jmapjolt

import rs.ltt.jmap.client.api.ErrorResponseException
import rs.ltt.jmap.client.api.UnauthorizedException

/**
 * Turns JMAP client failures into messages a user can act on.
 *
 * [ErrorResponseException] carries no message of its own, so an unhandled one surfaces as the bare
 * class name. Its [rs.ltt.jmap.common.ErrorResponse] holds the useful part (status + detail).
 *
 * Servers with two-factor authentication enabled (Stalwart, for one) reject the account password on
 * JMAP: TOTP has no place in the protocol, so a per-app password is the only way in.
 */
object AuthError {

    private const val MAX_CAUSE_DEPTH = 8

    /** Walks the cause chain: futures wrap the real failure in ExecutionException. */
    private fun causes(error: Throwable): Sequence<Throwable> = sequence {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            yield(current)
            val next = current.cause
            current = if (next === current) null else next
            depth++
        }
    }

    /** HTTP status reported by the server, when the failure carries one. */
    private fun statusOf(error: Throwable): Int? = causes(error)
        .filterIsInstance<ErrorResponseException>()
        .firstNotNullOfOrNull { it.errorResponse?.status }

    /** True when the server refused the credentials rather than failing for another reason. */
    fun isAuthFailure(error: Throwable): Boolean {
        if (causes(error).any { it is UnauthorizedException }) return true
        val status = statusOf(error)
        return status == 401 || status == 403
    }

    /**
     * Human-readable description of [error], with an app-password hint when the server rejected the
     * credentials.
     */
    fun describe(context: android.content.Context, error: Throwable): String {
        if (isAuthFailure(error)) return context.getString(R.string.error_auth_app_password)
        return detailOf(error) ?: error.message ?: error::class.java.simpleName
    }

    private fun detailOf(error: Throwable): String? = causes(error)
        .filterIsInstance<ErrorResponseException>()
        .firstNotNullOfOrNull { exception ->
            val response = exception.errorResponse ?: return@firstNotNullOfOrNull null
            val detail = response.detail ?: response.title
            listOfNotNull(
                response.status.takeIf { it != 0 }?.let { "HTTP $it" },
                detail?.takeIf { it.isNotBlank() }
            ).joinToString(": ").ifBlank { null }
        }
}
