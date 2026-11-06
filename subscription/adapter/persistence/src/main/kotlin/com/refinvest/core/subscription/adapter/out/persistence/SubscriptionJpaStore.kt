package com.refinvest.core.subscription.adapter.out.persistence

import org.springframework.data.repository.CrudRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface SubscriptionJpaStore : CrudRepository<SubscriptionJpaEntity, Long> {
    // The member exists before its first subscription, so it also fences concurrent initial creation.
    @Query(value = "SELECT id FROM members WHERE id = :memberId FOR UPDATE", nativeQuery = true)
    fun lockMemberById(@Param("memberId") memberId: Long): Long?
}
