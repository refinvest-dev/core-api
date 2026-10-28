package com.refinvest.core.tradingreview.port

/** Shared database fence. Admission/publication checks retain a lock until their transaction ends. */
interface TradingNormalizationTransitionGate {
    fun requireAdmission()
    fun publicationAllowed(): Boolean
    /** The permit spans durable claim, HTTP send and durable receipt; null means paused/busy. */
    fun <T : Any> withCreatePermit(action: () -> T): T?
}
