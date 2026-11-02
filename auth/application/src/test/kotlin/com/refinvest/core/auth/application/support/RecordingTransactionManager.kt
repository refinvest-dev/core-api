package com.refinvest.core.auth.application.support

import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus

internal class RecordingTransactionManager : PlatformTransactionManager {
    var begins = 0
    override fun getTransaction(definition: TransactionDefinition?): TransactionStatus {
        begins++
        return SimpleTransactionStatus()
    }
    override fun commit(status: TransactionStatus) = Unit
    override fun rollback(status: TransactionStatus) = Unit
}
