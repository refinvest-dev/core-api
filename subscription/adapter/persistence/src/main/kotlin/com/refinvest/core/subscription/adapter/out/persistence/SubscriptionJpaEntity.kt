package com.refinvest.core.subscription.adapter.out.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "subscriptions")
class SubscriptionJpaEntity(
    @Id
    @Column(name = "member_id")
    var memberId: Long,
    @Column(nullable = false, length = 20)
    var tier: String,
)
