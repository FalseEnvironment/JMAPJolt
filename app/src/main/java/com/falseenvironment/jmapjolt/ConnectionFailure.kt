package com.falseenvironment.jmapjolt

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException
import rs.ltt.jmap.client.api.UnauthorizedException

/** How much of the device network the app can see right now. */
internal enum class NetworkStatus { NO_NETWORK, NO_INTERNET, ONLINE }

/** The user-facing family a sync failure belongs to. */
internal enum class ConnectionProblem {
    OFFLINE,
    NO_INTERNET,
    UNREACHABLE,
    TIMEOUT,
    TLS,
    SERVER_ERROR,
    REQUEST_REJECTED,
    RATE_LIMIT,
    AUTH,
    UNKNOWN,
}

internal data class ConnectionFailure(
    val problem: ConnectionProblem,
    val httpStatus: Int? = null,
)

/**
 * Maps a JMAP/OkHttp failure to one of the messages the connection banner can show.
 * Kept free of Android so the mapping stays unit-testable.
 */
internal fun classifyConnectionFailure(
    error: Throwable,
    network: NetworkStatus,
): ConnectionFailure {
    val chain = AuthError.causes(error).toList()
    val status = AuthError.statusOf(error)

    val problem = when {
        chain.any { it is UnauthorizedException } || status == 401 || status == 403 ->
            ConnectionProblem.AUTH
        status == 429 -> ConnectionProblem.RATE_LIMIT
        status != null && status >= 500 -> ConnectionProblem.SERVER_ERROR
        status != null && status >= 400 -> ConnectionProblem.REQUEST_REJECTED
        chain.any { it is SSLException } -> ConnectionProblem.TLS
        network == NetworkStatus.NO_NETWORK -> ConnectionProblem.OFFLINE
        chain.any { it is UnknownHostException || it is ConnectException } ->
            if (network == NetworkStatus.NO_INTERNET) ConnectionProblem.NO_INTERNET
            else ConnectionProblem.UNREACHABLE
        chain.any { it is SocketTimeoutException || it is TimeoutException } ->
            if (network == NetworkStatus.NO_INTERNET) ConnectionProblem.NO_INTERNET
            else ConnectionProblem.TIMEOUT
        chain.any { it is java.io.IOException } ->
            if (network == NetworkStatus.NO_INTERNET) ConnectionProblem.NO_INTERNET
            else ConnectionProblem.UNREACHABLE
        else -> ConnectionProblem.UNKNOWN
    }
    return ConnectionFailure(problem, status?.takeIf { it > 0 })
}

/**
 * Human-readable cause chain for the "Details" sheet. Local-only: never sent anywhere.
 * Package names, timestamps and wrapper exceptions are dropped so the log stays
 * scannable; each line is `  Type: message`.
 */
internal fun describeFailureChain(error: Throwable): String =
    AuthError.causes(error)
        .filterNot { it.isWrapperNoise() }
        .take(MAX_CAUSE_LINES)
        .joinToString("\n") { cause ->
            val message = cause.readableMessage()
            if (message != null) "  ${cause.readableName()}: $message"
            else "  ${cause.readableName()}"
        }
        .ifBlank { "  ${error.readableName()}" }

private const val MAX_CAUSE_LINES = 8
private const val MAX_CAUSE_MESSAGE_CHARS = 300

private fun Throwable.readableName(): String =
    javaClass.simpleName.ifBlank { javaClass.name.substringAfterLast('.') }

/** ExecutionException and friends only repeat their cause; the cause line is enough. */
private fun Throwable.isWrapperNoise(): Boolean =
    cause != null && (message.isNullOrBlank() || message == cause.toString())

private fun Throwable.readableMessage(): String? =
    message
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let {
            if (it.length > MAX_CAUSE_MESSAGE_CHARS) {
                it.take(MAX_CAUSE_MESSAGE_CHARS - 1) + "…"
            } else {
                it
            }
        }
