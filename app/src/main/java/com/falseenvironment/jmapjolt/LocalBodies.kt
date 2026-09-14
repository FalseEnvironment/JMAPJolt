package com.falseenvironment.jmapjolt

/**
 * Carries downloaded bodies, and the preview rebuilt from them, across list fetches.
 *
 * List fetches skip bodies, so every sync hands back rows with an empty body and the
 * server `preview`. A row whose body was downloaded also holds the preview rebuilt from
 * it (see [PreviewText.fromBody]). JMAP email content never changes for a given id, so
 * both stay valid: without this the next sync, or the next app start, would put the
 * server snippet back and drop the body from the offline cache.
 *
 * Rows only match within the same account, because JMAP ids are scoped per account.
 * A blank account on either side (rows built without one) still matches.
 */
internal fun List<DisplayEmail>.keepLocalBodies(known: Collection<DisplayEmail>): List<DisplayEmail> {
    val byId = known.filter { it.fullBody.isNotBlank() }.groupBy { it.id }
    if (byId.isEmpty()) return this
    return map { row ->
        if (row.fullBody.isNotBlank()) return@map row
        val local = byId[row.id]?.firstOrNull { sameAccount(it.accountEmail, row.accountEmail) }
            ?: return@map row
        row.copy(fullBody = local.fullBody, preview = local.preview.ifBlank { row.preview })
    }
}

private fun sameAccount(a: String, b: String): Boolean =
    a.isBlank() || b.isBlank() || a.equals(b, ignoreCase = true)
