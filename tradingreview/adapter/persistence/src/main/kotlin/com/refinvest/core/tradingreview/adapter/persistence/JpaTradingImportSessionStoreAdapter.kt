package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.TradingImportSession
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportSessionId
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportStatus
import com.refinvest.core.tradingreview.port.outbound.persistence.importsession.TradingImportSessionStore
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager

@Repository
class JpaTradingImportSessionStoreAdapter(
    private val sessions: TradingImportSessionJpaStore,
    private val artifacts: TradingSourceArtifactJpaRows,
    private val accounts: TradingAccountJpaStore,
    private val books: TradingBookJpaStore,
    private val snapshots: TradingImportSessionSnapshots,
    private val entityManager: EntityManager,
) : TradingImportSessionStore {
    @Transactional
    override fun insert(session: TradingImportSession) {
        requireTransaction()
        require(session.version == 0L && session.status == TradingImportStatus.RECEIVED &&
            session.deletionGeneration == 0L && !session.deletionPending && session.artifacts.isEmpty() &&
            session.activeNormalizationRunId == null && session.createdLedgerRevisionId == null)
        require(parentActive(session.bookId.value, session.owner.value, session.declaredPositionMode.name)) {
            "Session requires an active parent with matching owner and mode"
        }
        val entity = TradingImportSessionPersistenceMapper.toEntity(session)
        entityManager.persist(entity)
        entityManager.flush()
        entityManager.detach(entity)
    }

    override fun find(id: TradingImportSessionId, owner: MemberId, forUpdate: Boolean): TradingImportSession? {
        if (forUpdate) {
            requireTransaction()
            if (sessions.lockOwned(id.value, owner.value) == null) return null
        }
        return snapshots.find(id, owner)
    }

    @Transactional
    override fun update(session: TradingImportSession): Boolean {
        requireTransaction()
        val initial = sessions.readSnapshot(session.id.value, session.owner.value) ?: return false
        if (!parentActive(initial.bookId, initial.owner)) return false
        val current = find(session.id, session.owner, true) ?: return false
        artifacts.lockSlots(session.id.value)
        val stored = artifacts.readSession(session.id.value).map(TradingImportSessionPersistenceMapper::toRecord)
        val plan = TradingImportSessionWritePlan.prepare(current, session, stored) ?: return false
        Math.addExact(session.version, 1)
        plan.changed.forEach { Math.addExact(it.version, 1) }
        val entity = sessions.findById(session.id.value).orElseThrow()
        try {
            entityManager.refresh(entity)
            check(entity.version == session.version)
            TradingImportSessionPersistenceMapper.apply(plan.after, entity)
            entityManager.flushTradingSnapshot(entity, session.version) { entity.version }
        } finally { entityManager.detach(entity) }
        plan.added.forEach { record ->
            val added = TradingImportSessionPersistenceMapper.toEntity(record)
            entityManager.persist(added)
            entityManager.flush()
            entityManager.detach(added)
        }
        plan.changed.forEach { record ->
            val changed = artifacts.findById(record.id).orElseThrow()
            try {
                entityManager.refresh(changed)
                check(changed.version == record.version)
                TradingImportSessionPersistenceMapper.apply(record, changed)
                entityManager.flushTradingSnapshot(changed, record.version) { changed.version }
            } finally { entityManager.detach(changed) }
        }
        return true
    }

    private fun parentActive(bookId: Long, owner: Long, mode: String? = null): Boolean {
        val initialBook = books.readSnapshot(bookId, owner) ?: return false
        if (accounts.lockOwned(initialBook.accountId, owner) == null) return false
        if (accounts.readSnapshot(initialBook.accountId, owner)?.status != "ACTIVE") return false
        if (books.lockOwned(bookId, owner) == null) return false
        val parent = books.readSnapshot(bookId, owner) ?: return false
        return parent.status == "ACTIVE" && (mode == null || parent.positionMode == mode)
    }

    override fun deleted(id: TradingImportSessionId, owner: MemberId): Boolean = sessions.deleted(id.value, owner.value)

    private fun requireTransaction() = check(TransactionSynchronizationManager.isActualTransactionActive()) {
        "Command write or lock requires an enclosing transaction"
    }
}
