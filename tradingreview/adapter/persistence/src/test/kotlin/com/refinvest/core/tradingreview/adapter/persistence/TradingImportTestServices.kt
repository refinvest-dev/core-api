package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.tradingreview.application.importsession.create.CreateTradingImportSessionService
import com.refinvest.core.tradingreview.application.importsession.delete.DeleteTradingImportSessionService
import com.refinvest.core.tradingreview.application.importsession.deleteartifact.DeleteTradingSourceArtifactService
import com.refinvest.core.tradingreview.application.importsession.get.GetTradingImportSessionService
import com.refinvest.core.tradingreview.application.importsession.list.ListTradingImportsService
import com.refinvest.core.tradingreview.application.importsession.upload.UploadTradingImportArtifactService
import com.refinvest.core.tradingreview.port.inbound.importsession.create.CreateTradingImportSessionUseCase
import com.refinvest.core.tradingreview.port.inbound.importsession.delete.DeleteTradingImportSessionUseCase
import com.refinvest.core.tradingreview.port.inbound.importsession.deleteartifact.DeleteTradingSourceArtifactUseCase
import com.refinvest.core.tradingreview.port.inbound.importsession.get.GetTradingImportSessionUseCase
import com.refinvest.core.tradingreview.port.inbound.importsession.list.ListTradingImportsUseCase
import com.refinvest.core.tradingreview.port.inbound.importsession.upload.UploadTradingImportArtifactUseCase
import com.refinvest.core.tradingreview.port.outbound.idempotency.TradingIdempotencyStore
import com.refinvest.core.tradingreview.port.outbound.identity.TradingIdGenerator
import com.refinvest.core.tradingreview.port.outbound.member.TradingMemberProvider
import com.refinvest.core.tradingreview.port.outbound.normalization.policy.TradingVersionPins
import com.refinvest.core.tradingreview.port.outbound.persistence.account.TradingAccountStore
import com.refinvest.core.tradingreview.port.outbound.persistence.book.TradingBookStore
import com.refinvest.core.tradingreview.port.outbound.persistence.deletion.DeletionRequestStore
import com.refinvest.core.tradingreview.port.outbound.persistence.deletion.DeletionScopeInventory
import com.refinvest.core.tradingreview.port.outbound.persistence.importsession.TradingImportSessionReader
import com.refinvest.core.tradingreview.port.outbound.persistence.importsession.TradingImportSessionStore
import com.refinvest.core.tradingreview.port.outbound.persistence.importsession.TradingSourceArtifactReader
import com.refinvest.core.tradingreview.port.outbound.persistence.ledger.TradingLedgerPublicationReader
import com.refinvest.core.tradingreview.port.outbound.storage.raw.RawArtifactStorage
import java.time.Clock
import org.springframework.transaction.support.TransactionTemplate

// Test-only composition keeps the PostgreSQL scenarios concise; production services remain independent.
internal class TradingImportTestServices(
    members: TradingMemberProvider,
    ids: TradingIdGenerator,
    accounts: TradingAccountStore,
    books: TradingBookStore,
    sessions: TradingImportSessionStore,
    artifacts: TradingSourceArtifactReader,
    deletions: DeletionRequestStore,
    idempotency: TradingIdempotencyStore,
    storage: RawArtifactStorage,
    versions: TradingVersionPins,
    transactions: TransactionTemplate,
    clock: Clock,
    revisions: TradingLedgerPublicationReader,
    reader: TradingImportSessionReader,
    deletionInventory: DeletionScopeInventory,
) :
    CreateTradingImportSessionUseCase by CreateTradingImportSessionService(members, ids, accounts, books, sessions, idempotency, versions, clock),
    GetTradingImportSessionUseCase by GetTradingImportSessionService(members, accounts, books, clock, revisions, reader),
    ListTradingImportsUseCase by ListTradingImportsService(members, accounts, books, clock, revisions, reader),
    UploadTradingImportArtifactUseCase by UploadTradingImportArtifactService(members, ids, accounts, books, sessions, artifacts, idempotency, storage, transactions, clock),
    DeleteTradingImportSessionUseCase by DeleteTradingImportSessionService(members, ids, accounts, books, sessions, deletions, idempotency, clock, deletionInventory),
    DeleteTradingSourceArtifactUseCase by DeleteTradingSourceArtifactService(members, ids, accounts, books, sessions, artifacts, deletions, idempotency, clock)
