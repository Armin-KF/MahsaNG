package com.v2ray.ang.ui.tv

import java.net.URI

/** Pure display helpers for TV screens. Kept free of Android types so they run as plain JVM tests. */
object TvFormat {
    private const val MASK = "•"

    /** Mahsa's serverless config carries a dummy proxy outbound with this tag; its address is never contacted. */
    const val SERVERLESS_TAG = "fakeproxy-out"

    /** A TV is a shared screen: show enough of a host to recognise it, not enough to copy it. */
    fun maskHost(host: String?): String {
        val h = host?.trim().orEmpty()
        if (h.isEmpty()) return ""
        if (h.contains(':')) return h.substringBefore(':') + ":" + MASK
        val parts = h.split('.')
        if (parts.size == 4 && parts.all { it.toIntOrNull() != null }) return "${parts[0]}.$MASK.${parts[3]}"
        if (parts.size >= 2) {
            val first = parts.first()
            return first.take(minOf(3, (first.length + 1) / 2)) + MASK + "." + parts.last()
        }
        return h.take(minOf(3, (h.length + 1) / 2)) + MASK
    }

    /** Masked "host : port", isolated so an RTL locale can't reorder the digits of an address. */
    fun endpoint(host: String?, port: Int?): String {
        val masked = maskHost(host)
        return if (masked.isEmpty()) "" else "\u2066$masked : $port\u2069"
    }

    /** Subscription URLs often embed an access token; only the host is safe to display. */
    fun urlHost(url: String?): String = try {
        URI(url.orEmpty().trim()).host.orEmpty()
    } catch (e: Exception) {
        ""
    }
}
