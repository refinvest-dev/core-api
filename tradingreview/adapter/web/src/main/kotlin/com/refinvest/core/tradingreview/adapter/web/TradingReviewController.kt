package com.refinvest.core.tradingreview.adapter.web

import com.refinvest.core.tradingreview.port.inbound.account.TradingAccountResult
import com.refinvest.core.tradingreview.port.inbound.account.create.CreateTradingAccountCommand
import com.refinvest.core.tradingreview.port.inbound.account.create.CreateTradingAccountUseCase
import com.refinvest.core.tradingreview.port.inbound.account.delete.DeleteTradingAccountCommand
import com.refinvest.core.tradingreview.port.inbound.account.delete.DeleteTradingAccountUseCase
import com.refinvest.core.tradingreview.port.inbound.account.get.GetTradingAccountQuery
import com.refinvest.core.tradingreview.port.inbound.account.get.GetTradingAccountUseCase
import com.refinvest.core.tradingreview.port.inbound.account.list.ListTradingAccountsQuery
import com.refinvest.core.tradingreview.port.inbound.account.list.ListTradingAccountsUseCase
import com.refinvest.core.tradingreview.port.inbound.book.TradingBookResult
import com.refinvest.core.tradingreview.port.inbound.book.create.CreateTradingBookCommand
import com.refinvest.core.tradingreview.port.inbound.book.create.CreateTradingBookUseCase
import com.refinvest.core.tradingreview.port.inbound.book.delete.DeleteTradingBookCommand
import com.refinvest.core.tradingreview.port.inbound.book.delete.DeleteTradingBookUseCase
import com.refinvest.core.tradingreview.port.inbound.book.get.GetTradingBookQuery
import com.refinvest.core.tradingreview.port.inbound.book.get.GetTradingBookUseCase
import com.refinvest.core.tradingreview.port.inbound.book.list.ListTradingBooksQuery
import com.refinvest.core.tradingreview.port.inbound.book.list.ListTradingBooksUseCase
import com.refinvest.core.tradingreview.port.inbound.deletion.PurgeComponentStatus
import com.refinvest.core.tradingreview.port.inbound.deletion.TradingDeletion
import com.refinvest.core.tradingreview.port.inbound.deletion.get.GetTradingDeletionRequestUseCase

import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.domain.valueobject.TradingAccountId
import com.refinvest.core.tradingreview.domain.valueobject.TradingBookId
import com.refinvest.core.tradingreview.domain.valueobject.TradingPositionMode
import com.refinvest.core.tradingreview.domain.valueobject.TradingProductFamily
import com.refinvest.core.tradingreview.domain.valueobject.TradingSettlementAsset
import com.refinvest.core.tradingreview.domain.valueobject.TradingVenue
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import java.time.Instant
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

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
        fun from(account: TradingAccountResult) = TradingAccountResponse(account.id.value.toString(), account.venue,
            account.displayName, account.status.name, account.createdAt)
    }
}

data class TradingBookResponse(val id: String, val tradingAccountId: String, val venue: TradingVenue,
                               val productFamily: TradingProductFamily, val positionMode: TradingPositionMode,
                               val settlementAsset: TradingSettlementAsset, val reviewTimezone: String,
                               val status: String, val createdAt: Instant) {
    companion object {
        fun from(book: TradingBookResult) = TradingBookResponse(book.id.value.toString(), book.accountId.value.toString(),
            book.venue, book.productFamily, book.positionMode, book.settlementAsset, book.reviewTimezone,
            book.status.name, book.createdAt)
    }
}

data class TradingPageResponse<T>(val items: List<T>, val page: Int, val size: Int, val total: Long)

data class AffectedResourceSummary(val accounts: Long, val books: Long, val importSessions: Long = 0,
                                   val artifacts: Long = 0, val ledgerRevisions: Long = 0,
                                   val analysisRuns: Long = 0, val reprocessingRuns: Long = 0,
                                   val snapshotStatus: String = "LEGACY_PARTIAL")

data class TradingDeletionResponse(val id: String, val targetType: String, val targetId: String,
                                   val requestedScope: String, val state: String,
                                   val affectedResources: AffectedResourceSummary, val revokedAt: Instant,
                                   val primaryPurgeStatus: String, val objectPurgeStatus: String,
                                   val computeRuntimePurgeStatus: String, val backupPurgeStatus: String,
                                   val backupPurgeDueAt: Instant?, val retryable: Boolean,
                                   val requestedAt: Instant, val updatedAt: Instant,
                                   val completedAt: Instant?, val failure: TradingDeletionFailure?) {
    companion object {
        fun from(value: TradingDeletion) = TradingDeletionResponse(value.id.toString(), value.targetType,
            value.targetId.toString(), value.requestedScope, value.state,
            AffectedResourceSummary(value.accountCount, value.bookCount, value.importSessionCount, value.artifactCount,
                value.ledgerRevisionCount, value.analysisRunCount, value.reprocessingRunCount, value.countSnapshotStatus), value.revokedAt,
            value.primaryPurgeStatus.name, value.objectPurgeStatus.name, value.computeRuntimePurgeStatus.name,
            value.backupPurgeStatus.name,
            value.backupPurgeDueAt.takeUnless { value.backupPurgeStatus == PurgeComponentStatus.NOT_APPLICABLE },
            value.retryable, value.requestedAt, value.updatedAt,
            value.completedAt, value.failureCode?.let { TradingDeletionFailure(it, "Deletion could not be completed", value.retryable) })
    }
}

data class TradingDeletionFailure(val code: String, val message: String, val retryable: Boolean)

@RestController
@RequestMapping("/trading-accounts")
class TradingAccountController(
    private val createAccount: CreateTradingAccountUseCase,
    private val getAccount: GetTradingAccountUseCase,
    private val listAccounts: ListTradingAccountsUseCase,
    private val deleteAccount: DeleteTradingAccountUseCase,
    private val createBook: CreateTradingBookUseCase,
    private val listBooks: ListTradingBooksUseCase,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestHeader("Idempotency-Key") key: String,
               @Valid @RequestBody request: CreateTradingAccountRequest) =
        TradingAccountResponse.from(createAccount.execute(CreateTradingAccountCommand(request.venue, request.displayName, key)))

    @GetMapping("/{tradingAccountId}")
    fun get(@PathVariable tradingAccountId: Long) = TradingAccountResponse.from(getAccount.execute(GetTradingAccountQuery(TradingAccountId(tradingAccountId))))

    @GetMapping
    fun list(@RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "20") size: Int): TradingPageResponse<TradingAccountResponse> {
        val result = listAccounts.execute(ListTradingAccountsQuery(page, size))
        return TradingPageResponse(result.items.map(TradingAccountResponse::from), result.page, result.size, result.total)
    }

    @DeleteMapping("/{tradingAccountId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun delete(@PathVariable tradingAccountId: Long, @RequestHeader("Idempotency-Key") key: String) =
        TradingDeletionResponse.from(deleteAccount.execute(DeleteTradingAccountCommand(TradingAccountId(tradingAccountId), key)))

    @PostMapping("/{tradingAccountId}/books")
    @ResponseStatus(HttpStatus.CREATED)
    fun createBook(@PathVariable tradingAccountId: Long, @RequestHeader("Idempotency-Key") key: String,
                   @Valid @RequestBody request: CreateTradingBookRequest) = TradingBookResponse.from(
        createBook.execute(CreateTradingBookCommand(TradingAccountId(tradingAccountId), request.displayName, request.productFamily, request.positionMode, request.settlementAsset, request.reviewTimezone, key)))

    @GetMapping("/{tradingAccountId}/books")
    fun listBooks(@PathVariable tradingAccountId: Long, @RequestParam(defaultValue = "0") page: Int,
                  @RequestParam(defaultValue = "20") size: Int): TradingPageResponse<TradingBookResponse> {
        val result = listBooks.execute(ListTradingBooksQuery(TradingAccountId(tradingAccountId), page, size))
        return TradingPageResponse(result.items.map(TradingBookResponse::from), result.page, result.size, result.total)
    }
}

@RestController
@RequestMapping("/trading-books")
class TradingBookController(
    private val getBook: GetTradingBookUseCase,
    private val deleteBook: DeleteTradingBookUseCase,
) {
    @GetMapping("/{tradingBookId}")
    fun get(@PathVariable tradingBookId: Long) = TradingBookResponse.from(getBook.execute(GetTradingBookQuery(TradingBookId(tradingBookId))))

    @DeleteMapping("/{tradingBookId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun delete(@PathVariable tradingBookId: Long, @RequestHeader("Idempotency-Key") key: String) =
        TradingDeletionResponse.from(deleteBook.execute(DeleteTradingBookCommand(TradingBookId(tradingBookId), key)))
}

@RestController
@RequestMapping("/trading-deletion-requests")
class TradingDeletionController(private val getDeletion: GetTradingDeletionRequestUseCase) {
    @GetMapping("/{deletionRequestId}")
    fun get(@PathVariable deletionRequestId: Long) = TradingDeletionResponse.from(getDeletion.get(deletionRequestId))
}
