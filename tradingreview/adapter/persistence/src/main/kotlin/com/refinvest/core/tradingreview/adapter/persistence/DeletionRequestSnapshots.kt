package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.DeletionRequest
import com.refinvest.core.tradingreview.domain.valueobject.DeletionRequestId
import com.refinvest.core.tradingreview.domain.valueobject.DeletionScope
import org.springframework.stereotype.Component

@Component
class DeletionRequestSnapshots(private val requests: DeletionRequestJpaStore) {
    fun find(id: DeletionRequestId, owner: MemberId): DeletionRequest? = restore(requests.readOwned(id.value, owner.value))

    fun originalTarget(owner: MemberId, scope: DeletionScope): DeletionRequest? =
        restore(requests.readOriginalTarget(owner.value, scope.targetType.name, scope.target.value))

    private fun restore(rows: List<DeletionRequestSnapshotRow>): DeletionRequest? {
        val root = rows.firstOrNull()?.record ?: return null
        check(rows.all { it.record == root })
        return DeletionRequestPersistenceMapper.toDomain(root.copy(verifiedAbsent = rows.mapNotNull { row ->
            row.component?.let { it to requireNotNull(row.verifiedAt) }
        }.toMap()))
    }
}
