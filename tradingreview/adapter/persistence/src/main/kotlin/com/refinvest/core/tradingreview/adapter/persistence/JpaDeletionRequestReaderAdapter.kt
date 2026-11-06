package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.DeletionRequest
import com.refinvest.core.tradingreview.domain.valueobject.DeletionRequestId
import com.refinvest.core.tradingreview.port.outbound.persistence.deletion.DeletionRequestReader
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class JpaDeletionRequestReaderAdapter(private val snapshots: DeletionRequestSnapshots) : DeletionRequestReader {
    @Transactional(readOnly = true)
    override fun find(id: DeletionRequestId, owner: MemberId): DeletionRequest? = snapshots.find(id, owner)
}
