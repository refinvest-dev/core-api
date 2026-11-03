package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.tradingreview.application.account.create.CreateTradingAccountService
import com.refinvest.core.tradingreview.application.account.delete.DeleteTradingAccountService
import com.refinvest.core.tradingreview.application.account.get.GetTradingAccountService
import com.refinvest.core.tradingreview.application.account.list.ListTradingAccountsService
import com.refinvest.core.tradingreview.application.book.create.CreateTradingBookService
import com.refinvest.core.tradingreview.application.book.delete.DeleteTradingBookService
import com.refinvest.core.tradingreview.application.book.get.GetTradingBookService
import com.refinvest.core.tradingreview.application.book.list.ListTradingBooksService
import com.refinvest.core.tradingreview.port.inbound.account.create.CreateTradingAccountUseCase
import com.refinvest.core.tradingreview.port.inbound.account.delete.DeleteTradingAccountUseCase
import com.refinvest.core.tradingreview.port.inbound.account.get.GetTradingAccountUseCase
import com.refinvest.core.tradingreview.port.inbound.account.list.ListTradingAccountsUseCase
import com.refinvest.core.tradingreview.port.inbound.book.create.CreateTradingBookUseCase
import com.refinvest.core.tradingreview.port.inbound.book.delete.DeleteTradingBookUseCase
import com.refinvest.core.tradingreview.port.inbound.book.get.GetTradingBookUseCase
import com.refinvest.core.tradingreview.port.inbound.book.list.ListTradingBooksUseCase
import com.refinvest.core.tradingreview.port.outbound.idempotency.TradingIdempotencyStore
import com.refinvest.core.tradingreview.port.outbound.identity.TradingIdGenerator
import com.refinvest.core.tradingreview.port.outbound.member.TradingMemberProvider
import com.refinvest.core.tradingreview.port.outbound.persistence.account.TradingAccountReader
import com.refinvest.core.tradingreview.port.outbound.persistence.account.TradingAccountStore
import com.refinvest.core.tradingreview.port.outbound.persistence.book.TradingBookDeletionCascade
import com.refinvest.core.tradingreview.port.outbound.persistence.book.TradingBookReader
import com.refinvest.core.tradingreview.port.outbound.persistence.book.TradingBookStore
import com.refinvest.core.tradingreview.port.outbound.persistence.deletion.DeletionRequestStore
import com.refinvest.core.tradingreview.port.outbound.persistence.deletion.DeletionScopeInventory
import java.time.Clock

// Test-only composition keeps the PostgreSQL scenarios concise; production services remain independent.
internal class TradingAccountBookTestServices(
    members: TradingMemberProvider,
    ids: TradingIdGenerator,
    accounts: TradingAccountStore,
    books: TradingBookStore,
    deletions: DeletionRequestStore,
    idempotency: TradingIdempotencyStore,
    clock: Clock,
    accountReader: TradingAccountReader,
    bookReader: TradingBookReader,
    bookDeletionCascade: TradingBookDeletionCascade,
    deletionInventory: DeletionScopeInventory,
) :
    CreateTradingAccountUseCase by CreateTradingAccountService(members, ids, accounts, idempotency, clock),
    GetTradingAccountUseCase by GetTradingAccountService(members, accountReader),
    ListTradingAccountsUseCase by ListTradingAccountsService(members, accountReader),
    DeleteTradingAccountUseCase by DeleteTradingAccountService(members, ids, accounts, deletions, idempotency, clock, bookDeletionCascade, deletionInventory),
    CreateTradingBookUseCase by CreateTradingBookService(members, ids, accounts, books, idempotency, clock),
    GetTradingBookUseCase by GetTradingBookService(members, accountReader, bookReader),
    ListTradingBooksUseCase by ListTradingBooksService(members, accountReader, bookReader),
    DeleteTradingBookUseCase by DeleteTradingBookService(members, ids, accounts, books, deletions, idempotency, clock, deletionInventory)
