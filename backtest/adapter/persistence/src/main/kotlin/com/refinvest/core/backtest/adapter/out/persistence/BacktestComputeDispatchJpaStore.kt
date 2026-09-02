package com.refinvest.core.backtest.adapter.out.persistence

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.query.Param
import jakarta.persistence.LockModeType
import java.time.Instant

interface BacktestComputeDispatchJpaStore : CrudRepository<BacktestComputeDispatchJpaEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select dispatch from BacktestComputeDispatchJpaEntity dispatch
        where dispatch.status = :status
          and dispatch.nextAttemptAt <= :now
          and (dispatch.leaseExpiresAt is null or dispatch.leaseExpiresAt <= :now)
        order by dispatch.createdAt
        """,
    )
    fun findClaimable(
        @Param("now") now: Instant,
        @Param("status") status: BacktestComputeDispatchStatusJpa,
        pageable: Pageable,
    ): List<BacktestComputeDispatchJpaEntity>
}
