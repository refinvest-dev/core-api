package com.refinvest.core.subscription.adapter.out.persistence

import org.springframework.data.repository.Repository

interface SubscriptionJpaReader : Repository<SubscriptionJpaEntity, Long> {
    fun findByMemberId(memberId: Long): SubscriptionJpaEntity?
}
