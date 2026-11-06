package com.refinvest.core.tradingreview.domain.valueobject



data class TradingDataPolicy(
    val dataPolicyVersion: String = "TRADING_DATA_POLICY_V1",
    val retentionPolicy: String = "TRADING_RETENTION_V1",
    val maskingPolicyVersion: String = "SOURCE_EVIDENCE_MASK_V1",
    val deletionPolicyVersion: String = "TRADING_DELETION_V1",
)
