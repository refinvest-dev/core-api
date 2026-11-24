package com.refinvest.core.shared.infrastructure.http

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.context.propagation.TextMapSetter
import java.security.SecureRandom

data class TraceContextHeaders(val parent: String, val state: String?, val traceId: String) {
    companion object {
        private val random = SecureRandom()
        private val propagator = W3CTraceContextPropagator.getInstance()
        private val getter = object : TextMapGetter<Map<String, String>> {
            override fun keys(carrier: Map<String, String>) = carrier.keys
            override fun get(carrier: Map<String, String>?, key: String) = carrier?.get(key)
        }
        private val setter = TextMapSetter<MutableMap<String, String>> { carrier, key, value -> carrier?.set(key, value) }

        fun start(parents: List<String>, states: List<String>): TraceContextHeaders {
            val carrier = mutableMapOf<String, String>()
            if (parents.size == 1 && parents[0].length in 1..512) {
                carrier["traceparent"] = parents[0]
                val size = states.sumOf { it.length.toLong() } + (states.size - 1).coerceAtLeast(0)
                if (states.size <= 32 && size <= 512) carrier["tracestate"] = states.joinToString(",")
            }
            val remote = Span.fromContext(propagator.extract(Context.root(), carrier, getter)).spanContext
            val traceId = if (remote.isValid) remote.traceId else randomHex(16)
            val local = SpanContext.create(traceId, randomHex(8), remote.traceFlags, remote.traceState)
            val values = mutableMapOf<String, String>()
            propagator.inject(Context.root().with(Span.wrap(local)), values, setter)
            return TraceContextHeaders(values.getValue("traceparent"), values["tracestate"], traceId)
        }

        fun child(parent: String?, state: String?): TraceContextHeaders =
            start(listOfNotNull(parent), listOfNotNull(state))

        private fun randomHex(bytes: Int): String {
            val buffer = ByteArray(bytes)
            random.nextBytes(buffer)
            check(buffer.any { it != 0.toByte() }) { "Trace context unavailable" }
            return java.util.HexFormat.of().formatHex(buffer)
        }
    }
}
