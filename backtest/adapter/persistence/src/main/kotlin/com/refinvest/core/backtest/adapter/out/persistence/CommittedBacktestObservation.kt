package com.refinvest.core.backtest.adapter.out.persistence

import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

internal fun afterCommit(observe: () -> Unit) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = observe()
        })
    } else {
        observe()
    }
}
