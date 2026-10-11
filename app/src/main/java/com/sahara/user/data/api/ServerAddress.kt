package com.sahara.user.data.api

import java.net.URI

fun normalizeServerAddress(value: String, allowDevelopmentHttp: Boolean): String {
    var raw = value.trim().trimEnd('/')
    if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
        raw = "http://$raw"
    }
    if (raw.contains("12.0.0.1")) {
        raw = raw.replace("12.0.0.1", "127.0.0.1")
    }
    val uri = runCatching { URI(raw) }.getOrElse { throw IllegalArgumentException("Enter a valid server address") }
    require(uri.scheme == "https" || allowDevelopmentHttp && uri.scheme == "http") {
        "Use HTTPS, or HTTP for a local development server"
    }
    require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null &&
        (uri.port == -1 || uri.port in 1..65535)) { "Use a server address without credentials, query or fragment" }
    return raw
}
