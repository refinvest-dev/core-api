package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.valueobject.DeletionRequestId
import com.refinvest.core.tradingreview.port.deletion.DeletionRequestReader
import com.refinvest.core.tradingreview.port.deletion.TradingDeletion
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class JdbcDeletionRequestReader(private val jdbc: JdbcTemplate) : DeletionRequestReader {
    override fun find(id: DeletionRequestId, owner: MemberId): TradingDeletion? = DeletionRequestQueries(jdbc)
        .find("d.id=? AND d.owner_member_id=?", id.value, owner.value)?.let(TradingDeletion::fromDomain)
}
