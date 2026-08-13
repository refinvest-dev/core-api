package com.refinvest.core.common.domain

abstract class AggregateRoot<ID : Identifier<*>>(
    override val id: ID,
) : DomainEntity<ID>(id) {
    private val recordedDomainEvents = mutableListOf<DomainEvent>()

    protected fun record(event: DomainEvent) {
        recordedDomainEvents += event
    }

    internal fun pullDomainEvents(): List<DomainEvent> =
        recordedDomainEvents.toList().also { recordedDomainEvents.clear() }
}
