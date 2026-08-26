package com.refinvest.core.common.domain

abstract class DomainEntity<ID : Identifier<*>>(
    val id: ID,
)
