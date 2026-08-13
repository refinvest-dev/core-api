package com.refinvest.core.shared.infrastructure.id

import java.time.Clock

class SnowflakeIdGenerator(
    private val nodeId: Long,
    private val clock: Clock = Clock.systemUTC(),
) {
    private var lastTimestamp = -1L
    private var sequence = 0L

    init {
        require(nodeId in 0..MAX_NODE_ID) { "nodeId must be between 0 and $MAX_NODE_ID" }
    }

    @Synchronized
    fun next(): Long {
        var timestamp = clock.millis()
        check(timestamp >= CUSTOM_EPOCH_MILLIS) { "Clock is before the Snowflake epoch" }
        check(timestamp >= lastTimestamp) { "Clock moved backwards" }

        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) and MAX_SEQUENCE
            if (sequence == 0L) {
                timestamp = waitForNextMillis(lastTimestamp)
            }
        } else {
            sequence = 0L
        }

        lastTimestamp = timestamp
        return ((timestamp - CUSTOM_EPOCH_MILLIS) shl TIMESTAMP_SHIFT) or
            (nodeId shl NODE_SHIFT) or
            sequence
    }

    private fun waitForNextMillis(previousTimestamp: Long): Long {
        var timestamp = clock.millis()
        while (timestamp <= previousTimestamp) {
            Thread.onSpinWait()
            timestamp = clock.millis()
        }
        return timestamp
    }

    companion object {
        private const val CUSTOM_EPOCH_MILLIS = 1_704_067_200_000L // 2024-01-01T00:00:00Z
        private const val NODE_BITS = 10
        private const val SEQUENCE_BITS = 12
        private const val NODE_SHIFT = SEQUENCE_BITS
        private const val TIMESTAMP_SHIFT = NODE_BITS + SEQUENCE_BITS
        private const val MAX_NODE_ID = (1L shl NODE_BITS) - 1
        private const val MAX_SEQUENCE = (1L shl SEQUENCE_BITS) - 1
    }
}
