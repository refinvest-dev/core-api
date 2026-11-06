package com.refinvest.core.auth.adapter.out.persistence

import com.refinvest.core.auth.domain.RefreshSession
import com.refinvest.core.shared.kernel.member.MemberId

internal object RefreshSessionPersistenceMapper {
    fun toEntity(session: RefreshSession): RefreshSessionJpaEntity = RefreshSessionJpaEntity(
        session.jti, session.memberId.value, session.familyId, session.tokenFingerprint, session.issuedAt,
        session.expiresAt, session.revokedAt, session.replacedByJti)

    fun toDomain(entity: RefreshSessionJpaEntity): RefreshSession = RefreshSession.restore(
        entity.jti, MemberId(entity.memberId), entity.familyId, entity.tokenFingerprint, entity.issuedAt,
        entity.expiresAt, entity.revokedAt, entity.replacedByJti)
}
