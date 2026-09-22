package com.refinvest.core.backtest.adapter.out.compute

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse

internal class ComputeClientTimingInterceptor(
    private val registry: MeterRegistry,
    private val operation: String,
) : ClientHttpRequestInterceptor {
    override fun intercept(
        request: HttpRequest,
        body: ByteArray,
        execution: ClientHttpRequestExecution,
    ): ClientHttpResponse {
        val sample = Timer.start(registry)
        try {
            val response = execution.execute(request, body)
            sample.stop(timer(if (response.statusCode.is2xxSuccessful) "success" else "http_error"))
            return response
        } catch (exception: Exception) {
            sample.stop(timer("transport_error"))
            throw exception
        }
    }

    private fun timer(outcome: String): Timer = Timer.builder(METRIC)
        .tags("operation", operation, "outcome", outcome)
        .publishPercentileHistogram()
        .register(registry)

    private companion object {
        const val METRIC = "refinvest.core.compute.client"
    }
}
