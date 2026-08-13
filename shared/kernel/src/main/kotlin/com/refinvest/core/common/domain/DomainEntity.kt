package com.refinvest.core.common.domain

abstract class DomainEntity<ID : Identifier<*>>(
    open val id: ID,
)
