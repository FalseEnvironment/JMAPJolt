package com.falseenvironment.jmapjolt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

/**
 * draft-ietf-jmap-emailpush payload parsing: the ids carried by the push are what makes
 * the notification independent of the INBOX baseline, so a shape change must not silently
 * fall back to "no ids" without a test noticing.
 */
class EmailPushPayloadTest {

    @Test
    fun `parses account and delivered email ids`() {
        val payload = """
            {
              "@type": "EmailPush",
              "accountId": "A1412",
              "emails": [
                {"id": "M1", "subject": "one"},
                {"id": "M2", "subject": "two"}
              ],
              "state": "XH99813"
            }
        """.trimIndent().toByteArray()

        val parsed = parseEmailPushPayload(payload)

        assertEquals("A1412", parsed?.accountId)
        assertEquals(listOf("M1", "M2"), parsed?.emailIds)
    }

    @Test
    fun `drops entries without an id and duplicates`() {
        val payload = """
            {"@type":"EmailPush","emails":[{"id":"M1"},{"subject":"no id"},{"id":""},{"id":"M1"}]}
        """.trimIndent().toByteArray()

        val parsed = parseEmailPushPayload(payload)

        assertEquals(listOf("M1"), parsed?.emailIds)
        assertNull(parsed?.accountId)
    }

    @Test
    fun `state change payload is not an email push`() {
        val payload = """
            {"@type":"StateChange","changed":{"A1412":{"Email":"42"}}}
        """.trimIndent().toByteArray()

        assertNull(parseEmailPushPayload(payload))
    }

    @Test
    fun `empty or malformed payloads yield no ids`() {
        assertNull(parseEmailPushPayload("""{"@type":"EmailPush","emails":[]}""".toByteArray()))
        assertNull(parseEmailPushPayload("not json".toByteArray()))
        assertNull(parseEmailPushPayload(ByteArray(0)))
    }

    @Test
    fun `email push config is keyed by account with the draft properties`() {
        val config = JMapClient.EmailPushConfig(
            accountId = "A1412",
            filter = JSONObject()
                .put("operator", "AND")
                .put("conditions", JSONArray().put(JSONObject().put("notKeyword", "\$junk"))),
            properties = listOf("id"),
            urgency = "high"
        )

        val entry = config.toJson().getJSONObject("A1412")

        assertEquals("high", entry.getString("urgency"))
        assertEquals("id", entry.getJSONArray("properties").getString(0))
        assertEquals("AND", entry.getJSONObject("filter").getString("operator"))
    }
}
