package com.refinvest.core.backtest.adapter.out.compute

import com.refinvest.core.backtest.domain.backtest.BacktestResult
import com.refinvest.core.backtest.domain.backtest.BacktestResultMetrics
import com.refinvest.core.backtest.domain.backtest.Benchmark
import com.refinvest.core.backtest.domain.backtest.BuyAndHoldResult
import com.refinvest.core.backtest.domain.backtest.DataIntegrityStatus
import com.refinvest.core.backtest.domain.backtest.EquityCurvePoint
import com.refinvest.core.backtest.domain.backtest.SampleSizeWarning
import com.refinvest.core.backtest.domain.backtest.SignalExecutionDelay
import com.refinvest.core.backtest.domain.backtest.Trade
import com.refinvest.core.backtest.domain.valueobject.BacktestRunId
import com.refinvest.core.backtest.domain.valueobject.DatasetSnapshotId
import com.refinvest.core.backtest.domain.valueobject.EngineVersion
import com.refinvest.core.backtest.domain.valueobject.Period
import com.refinvest.core.backtest.port.outbound.compute.ComputeBacktestStatus
import com.refinvest.core.backtest.port.outbound.compute.ComputeBacktestStatusClient
import com.refinvest.core.backtest.port.outbound.compute.ComputeBacktestStatusLookup
import com.refinvest.core.backtest.port.outbound.compute.ComputeBacktestStatusValue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.net.http.HttpClient
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

@Component
class RestClientComputeBacktestStatusClient private constructor(
    private val restClient: RestClient,
    private val apiKey: String,
    private val objectMapper: ObjectMapper,
) : ComputeBacktestStatusClient {
    @Autowired
    constructor(
        @Value("\${refinvest.compute.base-url:http://localhost:8000}") baseUrl: String,
        @Value("\${refinvest.compute.api-key:}") apiKey: String,
        objectMapper: ObjectMapper,
    ) : this(createRestClient(baseUrl), apiKey, objectMapper)

    internal constructor(
        baseUrl: String,
        apiKey: String,
        objectMapper: ObjectMapper,
        restClientBuilder: RestClient.Builder,
    ) : this(restClientBuilder.baseUrl(baseUrl).build(), apiKey, objectMapper)

    override fun getBacktestStatus(
        computeRunId: String,
        backtestRunId: BacktestRunId,
    ): ComputeBacktestStatusLookup = try {
        restClient.get()
            .uri("/backtests/{runId}", computeRunId)
            .header("X-Internal-Api-Key", apiKey)
            .exchange { _, response ->
                when (response.statusCode.value()) {
                    200 -> response.bodyTo(String::class.java)
                        ?.let { body -> objectMapper.readValue(body, ComputeBacktestStatusResponse::class.java) }
                        ?.toLookup(backtestRunId)
                        ?: ComputeBacktestStatusLookup.RetryLater(RETRY_AFTER_TRANSIENT_FAILURE)
                    404 -> ComputeBacktestStatusLookup.NotFound
                    503 -> ComputeBacktestStatusLookup.RetryLater(RETRY_AFTER_SERVICE_UNAVAILABLE)
                    else -> ComputeBacktestStatusLookup.RetryLater(RETRY_AFTER_TRANSIENT_FAILURE)
                }
            }
    } catch (_: Exception) {
        ComputeBacktestStatusLookup.RetryLater(RETRY_AFTER_TRANSIENT_FAILURE)
    }

    private fun ComputeBacktestStatusResponse.toLookup(backtestRunId: BacktestRunId): ComputeBacktestStatusLookup.Found =
        ComputeBacktestStatusLookup.Found(
            ComputeBacktestStatus(
                status = ComputeBacktestStatusValue.valueOf(status),
                actualPeriod = actualPeriod?.let { Period(it.start, it.end) },
                datasetSnapshotId = datasetSnapshotId?.let(::DatasetSnapshotId),
                engineVersion = engineVersion?.let(::EngineVersion),
                result = result?.toDomain(backtestRunId),
                failureReason = failureReason,
            ),
        )

    private fun ComputeBacktestResultResponse.toDomain(backtestRunId: BacktestRunId): BacktestResult = BacktestResult(
        backtestRunId = backtestRunId,
        metrics = BacktestResultMetrics(
            totalReturn = metrics.totalReturn,
            cagr = metrics.cagr,
            mdd = metrics.mdd,
            sharpe = metrics.sharpe,
            winRate = metrics.winRate,
            tradeCount = metrics.tradeCount,
            avgTradeReturn = metrics.avgTradeReturn,
            avgHoldingPeriod = metrics.avgHoldingPeriod,
            profitFactor = metrics.profitFactor,
        ),
        equityCurve = equityCurve.map { EquityCurvePoint(it.date, it.value) },
        trades = trades.map {
            Trade(
                signalTime = it.signalTime,
                entryTime = it.entryTime,
                entryPrice = it.entryPrice,
                exitTime = it.exitTime,
                exitPrice = it.exitPrice,
                returnPct = it.returnPct,
                holdingPeriod = it.holdingPeriod,
            )
        },
        benchmark = Benchmark(
            primary = benchmark.primary.toDomain(),
            secondaryReference = benchmark.secondaryReference?.toDomain(),
        ),
        signalExecutionDelay = SignalExecutionDelay(
            median = signalExecutionDelay.median,
            max = signalExecutionDelay.max,
            distribution = signalExecutionDelay.distribution,
        ),
        sampleSizeWarning = SampleSizeWarning.valueOf(sampleSizeWarning),
        dataIntegrityStatus = DataIntegrityStatus(
            datasetSnapshotId = DatasetSnapshotId(dataIntegrityStatus.datasetSnapshotId),
            corporateActionsApplied = dataIntegrityStatus.corporateActionsApplied,
            pointInTimeValidationPassed = dataIntegrityStatus.pointInTimeValidationPassed,
        ),
    )

    private fun ComputeBuyAndHoldResultResponse.toDomain() = BuyAndHoldResult(
        totalReturn = totalReturn,
        cagr = cagr,
        mdd = mdd,
    )

    private data class ComputeBacktestStatusResponse(
        val status: String,
        val datasetSnapshotId: String? = null,
        val actualPeriod: ComputePeriodResponse? = null,
        val engineVersion: String? = null,
        val result: ComputeBacktestResultResponse? = null,
        val failureReason: String? = null,
    )

    private data class ComputePeriodResponse(val start: LocalDate, val end: LocalDate)

    private data class ComputeBacktestResultResponse(
        val backtestRunId: String,
        val metrics: ComputeBacktestResultMetricsResponse,
        val equityCurve: List<ComputeEquityCurvePointResponse>,
        val trades: List<ComputeTradeResponse>,
        val benchmark: ComputeBenchmarkResponse,
        val signalExecutionDelay: ComputeSignalExecutionDelayResponse,
        val sampleSizeWarning: String,
        val dataIntegrityStatus: ComputeDataIntegrityStatusResponse,
    )

    private data class ComputeBacktestResultMetricsResponse(
        val totalReturn: BigDecimal?,
        val cagr: BigDecimal?,
        val mdd: BigDecimal?,
        val sharpe: BigDecimal?,
        val winRate: BigDecimal?,
        val tradeCount: Int,
        val avgTradeReturn: BigDecimal?,
        val avgHoldingPeriod: BigDecimal?,
        val profitFactor: BigDecimal?,
    )

    private data class ComputeEquityCurvePointResponse(val date: LocalDate, val value: BigDecimal)

    private data class ComputeTradeResponse(
        val signalTime: Instant,
        val entryTime: Instant,
        val entryPrice: BigDecimal,
        val exitTime: Instant,
        val exitPrice: BigDecimal,
        val returnPct: BigDecimal,
        val holdingPeriod: Int,
    )

    private data class ComputeBenchmarkResponse(
        val primary: ComputeBuyAndHoldResultResponse,
        val secondaryReference: ComputeBuyAndHoldResultResponse? = null,
    )

    private data class ComputeBuyAndHoldResultResponse(
        val totalReturn: BigDecimal,
        val cagr: BigDecimal,
        val mdd: BigDecimal,
    )

    private data class ComputeSignalExecutionDelayResponse(
        val median: BigDecimal,
        val max: BigDecimal,
        val distribution: List<BigDecimal>,
    )

    private data class ComputeDataIntegrityStatusResponse(
        val datasetSnapshotId: String,
        val corporateActionsApplied: Boolean,
        val pointInTimeValidationPassed: Boolean,
    )

    private companion object {
        val RETRY_AFTER_SERVICE_UNAVAILABLE: Duration = Duration.ofSeconds(30)
        val RETRY_AFTER_TRANSIENT_FAILURE: Duration = Duration.ofSeconds(10)

        fun createRestClient(baseUrl: String): RestClient = RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(
                JdkClientHttpRequestFactory(
                    HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(),
                ),
            )
            .build()
    }
}
