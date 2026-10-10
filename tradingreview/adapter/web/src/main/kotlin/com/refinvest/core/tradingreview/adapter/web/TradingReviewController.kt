package com.refinvest.core.tradingreview.adapter.web

import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.port.*
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.time.Instant

data class CreateTradingAccountRequest(
    val venue: TradingVenue,
    @field:NotEmpty @field:Size(max = 100) val displayName: String,
)

data class CreateTradingBookRequest(
    @field:NotEmpty @field:Size(max = 100) val displayName: String,
    val productFamily: TradingProductFamily,
    val positionMode: TradingPositionMode,
    val settlementAsset: TradingSettlementAsset,
    @field:NotBlank val reviewTimezone: String,
)

data class TradingAccountResponse(val id: String, val venue: TradingVenue, val displayName: String,
                                  val status: String, val createdAt: Instant) {
    companion object {
        fun from(account: TradingAccount) = TradingAccountResponse(account.id.value.toString(), account.venue,
            account.displayName, account.status.name, account.createdAt)
    }
}

data class TradingBookResponse(val id: String, val tradingAccountId: String, val venue: TradingVenue,
                               val productFamily: TradingProductFamily, val positionMode: TradingPositionMode,
                               val settlementAsset: TradingSettlementAsset, val reviewTimezone: String,
                               val status: String, val createdAt: Instant) {
    companion object {
        fun from(book: TradingBook) = TradingBookResponse(book.id.value.toString(), book.accountId.value.toString(),
            book.venue, book.productFamily, book.positionMode, book.settlementAsset, book.reviewTimezone,
            book.status.name, book.createdAt)
    }
}

data class TradingPageResponse<T>(val items: List<T>, val page: Int, val size: Int, val total: Long)

data class AffectedResourceSummary(val accounts: Long, val books: Long, val importSessions: Long = 0,
                                   val artifacts: Long = 0, val ledgerRevisions: Long = 0,
                                   val analysisRuns: Long = 0, val reprocessingRuns: Long = 0)

data class TradingDeletionResponse(val id: String, val targetType: String, val targetId: String,
                                   val requestedScope: String, val state: String,
                                   val affectedResources: AffectedResourceSummary, val revokedAt: Instant,
                                   val primaryPurgeStatus: String, val objectPurgeStatus: String,
                                   val computeRuntimePurgeStatus: String, val backupPurgeStatus: String,
                                   val backupPurgeDueAt: Instant, val retryable: Boolean,
                                   val requestedAt: Instant, val updatedAt: Instant,
                                   val completedAt: Instant?, val failure: TradingDeletionFailure?) {
    companion object {
        fun from(value: TradingDeletion) = TradingDeletionResponse(value.id.toString(), value.targetType,
            value.targetId.toString(), value.requestedScope, value.state,
            AffectedResourceSummary(value.accountCount, value.bookCount), value.revokedAt,
            when (value.state) { "COMPLETED" -> "COMPLETED"; "FAILED" -> "FAILED"; else -> "NOT_STARTED" }, "NOT_APPLICABLE",
            "NOT_APPLICABLE", "IN_PROGRESS", value.backupPurgeDueAt,
            value.state != "COMPLETED" && value.state != "FAILED", value.requestedAt, value.updatedAt,
            value.completedAt, value.failureCode?.let { TradingDeletionFailure(it, "Deletion could not be completed", false) })
    }
}

data class TradingDeletionFailure(val code: String, val message: String, val retryable: Boolean)

@RestController
@RequestMapping("/trading-accounts")
class TradingAccountController(private val accounts: TradingAccountUseCase, private val books: TradingBookUseCase) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestHeader("Idempotency-Key") key: String,
               @Valid @RequestBody request: CreateTradingAccountRequest) =
        TradingAccountResponse.from(accounts.create(request.venue, request.displayName, key))

    @GetMapping("/{tradingAccountId}")
    fun get(@PathVariable tradingAccountId: Long) = TradingAccountResponse.from(accounts.get(TradingAccountId(tradingAccountId)))

    @GetMapping
    fun list(@RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "20") size: Int): TradingPageResponse<TradingAccountResponse> {
        val result = accounts.list(page, size)
        return TradingPageResponse(result.items.map(TradingAccountResponse::from), result.page, result.size, result.total)
    }

    @DeleteMapping("/{tradingAccountId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun delete(@PathVariable tradingAccountId: Long, @RequestHeader("Idempotency-Key") key: String) =
        TradingDeletionResponse.from(accounts.delete(TradingAccountId(tradingAccountId), key))

    @PostMapping("/{tradingAccountId}/books")
    @ResponseStatus(HttpStatus.CREATED)
    fun createBook(@PathVariable tradingAccountId: Long, @RequestHeader("Idempotency-Key") key: String,
                   @Valid @RequestBody request: CreateTradingBookRequest) = TradingBookResponse.from(
        books.create(TradingAccountId(tradingAccountId), request.displayName, request.productFamily,
            request.positionMode, request.settlementAsset, request.reviewTimezone, key))

    @GetMapping("/{tradingAccountId}/books")
    fun listBooks(@PathVariable tradingAccountId: Long, @RequestParam(defaultValue = "0") page: Int,
                  @RequestParam(defaultValue = "20") size: Int): TradingPageResponse<TradingBookResponse> {
        val result = books.list(TradingAccountId(tradingAccountId), page, size)
        return TradingPageResponse(result.items.map(TradingBookResponse::from), result.page, result.size, result.total)
    }
}

@RestController
@RequestMapping("/trading-books")
class TradingBookController(private val books: TradingBookUseCase) {
    @GetMapping("/{tradingBookId}")
    fun get(@PathVariable tradingBookId: Long) = TradingBookResponse.from(books.get(TradingBookId(tradingBookId)))

    @DeleteMapping("/{tradingBookId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun delete(@PathVariable tradingBookId: Long, @RequestHeader("Idempotency-Key") key: String) =
        TradingDeletionResponse.from(books.delete(TradingBookId(tradingBookId), key))
}

@RestController
@RequestMapping("/trading-deletion-requests")
class TradingDeletionController(private val getDeletion: GetTradingDeletionRequestUseCase) {
    @GetMapping("/{deletionRequestId}")
    fun get(@PathVariable deletionRequestId: Long) = TradingDeletionResponse.from(getDeletion.get(deletionRequestId))
}
