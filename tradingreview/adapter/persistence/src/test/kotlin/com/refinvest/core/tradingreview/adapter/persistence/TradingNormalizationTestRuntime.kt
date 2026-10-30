package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.tradingreview.domain.importsession.*
import com.refinvest.core.tradingreview.domain.valueobject.*
import com.refinvest.core.tradingreview.port.normalization.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper

/** Test-only row seeding for legacy snapshots and runtime fault scenarios. */
internal class TradingNormalizationTestRuntime(
    private val jdbc: JdbcTemplate, mapper: ObjectMapper,
    private val runtime: JdbcTradingNormalizationRuntimeAdapter = JdbcTradingNormalizationRuntimeAdapter(jdbc),
) : TradingNormalizationRuntime by runtime {
    fun findRun(id: String) = runtime.findRun(NormalizationRunId(id))
    fun seed(run: NormalizationRun, dispatch: NormalizationDispatch) {
        if (jdbc.execute(ConnectionCallback { !it.autoCommit }) == true) NormalizationAdmissionRows(jdbc).insert(run, dispatch)
        else TransactionTemplate(DataSourceTransactionManager(requireNotNull(jdbc.dataSource))).executeWithoutResult {
            NormalizationAdmissionRows(jdbc).insert(run, dispatch)
        }
    }
}
