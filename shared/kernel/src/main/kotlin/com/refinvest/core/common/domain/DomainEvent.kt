package com.refinvest.core.common.domain

import java.time.Instant

/** A module-internal domain byproduct, not an inter-module messaging contract. */
interface DomainEvent {
    val occurredAt: Instant
}
