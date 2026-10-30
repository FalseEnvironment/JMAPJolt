package com.falseenvironment.jmapjolt

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import rs.ltt.jmap.client.api.UnauthorizedException

/**
 * The OAuth reauth path keys off [AuthError.isUnauthorized], while the login screen
 * still uses [AuthError.isAuthFailure]: keep the two distinct and make sure wrapping
 * exceptions (futures, coroutines) do not hide the 401.
 */
class AuthErrorTest {

    @Test
    fun unauthorizedIsDetectedThroughWrappingCauses() {
        val error = IllegalStateException(
            "outer",
            RuntimeException(UnauthorizedException("401", emptyList()))
        )
        assertTrue(AuthError.isUnauthorized(error))
        assertTrue(AuthError.isAuthFailure(error))
    }

    @Test
    fun transportFailureIsNotAnAuthFailure() {
        val error = IOException("connection timed out")
        assertFalse(AuthError.isUnauthorized(error))
        assertFalse(AuthError.isAuthFailure(error))
    }
}
