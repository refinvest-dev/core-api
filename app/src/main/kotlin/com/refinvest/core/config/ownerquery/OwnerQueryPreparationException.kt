package com.refinvest.core.config.ownerquery

/** No raw cause, input, credential or private path crosses this boundary. */
class OwnerQueryPreparationException(val stage: Stage) : RuntimeException("Owner query preparation failed: ${stage.name}") {
    enum class Stage { INPUT, SNAPSHOT, CAPABILITIES, DEADLINE, CLEANUP }
}

internal class PreparationBudget(timeout: java.time.Duration, private val nanoTime: () -> Long) {
    private val started = nanoTime()
    private val limit = try {
        timeout.toNanos().also { require(it > 0) }
    } catch (_: Exception) {
        throw OwnerQueryPreparationException(OwnerQueryPreparationException.Stage.INPUT)
    }

    fun check() {
        if (Thread.currentThread().isInterrupted || nanoTime() - started >= limit) {
            throw OwnerQueryPreparationException(OwnerQueryPreparationException.Stage.DEADLINE)
        }
    }
}
