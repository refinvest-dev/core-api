package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.DeletionRequest
import com.refinvest.core.tradingreview.domain.valueobject.DeletionRequestId
import com.refinvest.core.tradingreview.domain.valueobject.DeletionRequestState
import com.refinvest.core.tradingreview.domain.valueobject.DeletionScope
import com.refinvest.core.tradingreview.port.outbound.persistence.deletion.DeletionRequestStore
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class JpaDeletionRequestStoreAdapter(
    private val snapshots: DeletionRequestSnapshots,
    private val entityManager: EntityManager,
) : DeletionRequestStore {
    @Transactional
    override fun insert(request: DeletionRequest) {
        require(request.state == DeletionRequestState.REQUESTED && request.attemptCount == 0 && request.failureCode == null &&
            request.version == 0L && request.verifiedAbsent.isEmpty())
        val entity = DeletionRequestPersistenceMapper.toEntity(request)
        entityManager.persist(entity)
        entityManager.flush()
        entityManager.detach(entity)
    }

    override fun find(id: DeletionRequestId, owner: MemberId): DeletionRequest? = snapshots.find(id, owner)
    override fun findByTarget(owner: MemberId, scope: DeletionScope): DeletionRequest? = snapshots.originalTarget(owner, scope)
}
