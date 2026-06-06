package com.falseenvironment.jmapjolt

data class AccountEntry(
    val email: String,
    val password: String,
    val serverUrl: String,
    val sessionUrl: String,
    val apiUrl: String,
    val accountId: String
) {
    override fun toString() =
        "AccountEntry(email=$email, serverUrl=$serverUrl, accountId=$accountId)"
}
