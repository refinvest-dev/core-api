package com.refinvest.core.backtest.adapter.out.compute

import com.refinvest.core.backtest.port.outbound.compute.ComputeIdempotencyKey
import com.refinvest.core.backtest.port.outbound.compute.ComputeIdempotencyKeyGenerator
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class ComputeIdempotencyKeyGeneratorAdapter : ComputeIdempotencyKeyGenerator {
    override fun next(): ComputeIdempotencyKey = ComputeIdempotencyKey(UUID.randomUUID())
}
