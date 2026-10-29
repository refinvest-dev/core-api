package com.refinvest.core.shared.infrastructure.http

import org.springframework.http.client.JdkClientHttpRequestFactory
import java.net.http.HttpClient
import java.time.Duration

class HttpClientTimeouts(
    val connect: Duration = Duration.ofSeconds(2),
    val read: Duration = Duration.ofSeconds(20),
) {
    init {
        require(connect > Duration.ZERO && read > Duration.ZERO) { "HTTP timeouts must be positive" }
    }

    fun requireBudgetBelow(duration: Duration): HttpClientTimeouts = apply {
        require(connect.plus(read) < duration) { "HTTP timeout budget must be shorter than the allowed duration" }
    }

    fun requestFactory(): JdkClientHttpRequestFactory = JdkClientHttpRequestFactory(
        HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(connect).build(),
    ).apply { setReadTimeout(read) }
}
