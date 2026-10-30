package com.falseenvironment.jmapjolt

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ExecutionException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import rs.ltt.jmap.client.api.ErrorResponseException
import rs.ltt.jmap.common.ErrorResponse
import rs.ltt.jmap.common.entity.ErrorType

class ConnectionFailureTest {

    private fun httpError(status: Int) =
        ErrorResponseException(ErrorResponse(ErrorType.NOT_REQUEST, status, "server says no"))

    @Test
    fun `no network wins over the exception type`() {
        val failure = classifyConnectionFailure(
            UnknownHostException("mail.example.com"),
            NetworkStatus.NO_NETWORK,
        )

        assertEquals(ConnectionProblem.OFFLINE, failure.problem)
    }

    @Test
    fun `unknown host on a network without internet is a connectivity problem`() {
        val failure = classifyConnectionFailure(
            UnknownHostException("mail.example.com"),
            NetworkStatus.NO_INTERNET,
        )

        assertEquals(ConnectionProblem.NO_INTERNET, failure.problem)
    }

    @Test
    fun `unknown host on a validated network is an unreachable server`() {
        val failure = classifyConnectionFailure(
            UnknownHostException("mail.example.com"),
            NetworkStatus.ONLINE,
        )

        assertEquals(ConnectionProblem.UNREACHABLE, failure.problem)
    }

    @Test
    fun `connection refused is an unreachable server`() {
        val failure = classifyConnectionFailure(
            ConnectException("Connection refused"),
            NetworkStatus.ONLINE,
        )

        assertEquals(ConnectionProblem.UNREACHABLE, failure.problem)
    }

    @Test
    fun `timeout is reported as a slow server`() {
        val failure = classifyConnectionFailure(
            SocketTimeoutException("timeout"),
            NetworkStatus.ONLINE,
        )

        assertEquals(ConnectionProblem.TIMEOUT, failure.problem)
    }

    @Test
    fun `tls failures are not mistaken for offline`() {
        val failure = classifyConnectionFailure(
            SSLHandshakeException("bad certificate"),
            NetworkStatus.ONLINE,
        )

        assertEquals(ConnectionProblem.TLS, failure.problem)
    }

    @Test
    fun `http 401 is an auth failure`() {
        val failure = classifyConnectionFailure(httpError(401), NetworkStatus.ONLINE)

        assertEquals(ConnectionProblem.AUTH, failure.problem)
        assertEquals(401, failure.httpStatus)
    }

    @Test
    fun `http 429 is rate limiting`() {
        val failure = classifyConnectionFailure(httpError(429), NetworkStatus.ONLINE)

        assertEquals(ConnectionProblem.RATE_LIMIT, failure.problem)
    }

    @Test
    fun `http 503 is a server error`() {
        val failure = classifyConnectionFailure(httpError(503), NetworkStatus.ONLINE)

        assertEquals(ConnectionProblem.SERVER_ERROR, failure.problem)
        assertEquals(503, failure.httpStatus)
    }

    @Test
    fun `http 400 is a rejected request`() {
        val failure = classifyConnectionFailure(httpError(400), NetworkStatus.ONLINE)

        assertEquals(ConnectionProblem.REQUEST_REJECTED, failure.problem)
    }

    @Test
    fun `execution exception is unwrapped to the real cause`() {
        val failure = classifyConnectionFailure(
            ExecutionException(httpError(503)),
            NetworkStatus.ONLINE,
        )

        assertEquals(ConnectionProblem.SERVER_ERROR, failure.problem)
    }

    @Test
    fun `non network errors stay generic`() {
        val failure = classifyConnectionFailure(
            IllegalStateException("No mail account found"),
            NetworkStatus.ONLINE,
        )

        assertEquals(ConnectionProblem.UNKNOWN, failure.problem)
    }

    @Test
    fun `details drop wrapper noise and keep the real cause`() {
        val detail = describeFailureChain(
            ExecutionException(UnknownHostException("mail.example.com"))
        )

        assertEquals("  UnknownHostException: mail.example.com", detail)
        assertTrue(!detail.contains("ExecutionException"))
        assertTrue(!detail.contains("java.net"))
    }

    @Test
    fun `details collapse multiline messages and keep simple names`() {
        val detail = describeFailureChain(
            ExecutionException(SSLHandshakeException("bad\n  certificate\tchain"))
        )

        assertEquals("  SSLHandshakeException: bad certificate chain", detail)
    }
}
