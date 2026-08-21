package com.refinvest.core.subscription.adapter.out.persistence

import org.springframework.data.repository.CrudRepository

interface SubscriptionJpaStore : CrudRepository<SubscriptionJpaEntity, Long>
