package com.refinvest.core.subscription.adapter.out.persistence

import com.refinvest.core.subscription.domain.Subscription
import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.subscription.port.outbound.persistence.SubscriptionStore
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager

@Repository
class JpaSubscriptionStoreAdapter(
    private val subscriptionJpaStore: SubscriptionJpaStore,
) : SubscriptionStore {
    @Transactional
    override fun save(subscription: Subscription) {
        lockMember(subscription.id)
        subscriptionJpaStore.save(SubscriptionPersistenceMapper.toEntity(subscription))
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun findByMemberId(memberId: MemberId): Subscription? {
        lockMember(memberId)
        return subscriptionJpaStore.findById(memberId.value).orElse(null)
            ?.let(SubscriptionPersistenceMapper::toDomain)
    }

    private fun lockMember(memberId: MemberId) {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "Subscription command access requires a transaction"
        }
        requireNotNull(subscriptionJpaStore.lockMemberById(memberId.value)) {
            "Subscription requires an existing member"
        }
    }
}
