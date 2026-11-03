package com.refinvest.core.tradingreview.port.outbound.compute.result

import com.refinvest.core.tradingreview.domain.*
import java.time.Instant

/** Transport bytes stay outside durable state; the descriptor contains only safe opaque identities and digests. */
data class NormalizationResultDescriptor(
    val jobId: String,
    val logicalJobId: String,
    val runId: String,
    val payloadId: String,
    val byteSize: Long,
    val payloadSha256: String,
    val attemptToken: String,
    val deletionGeneration: Long,
    val inputSetHash: String,
    val inputHashSpecificationVersion: String,
    val targetVersionSet: Map<String, Any?>,
    val resultSchemaVersion: String,
    val resultHashSpecificationVersion: String,
    val counts: Map<String, Any?>,
    val sectionDigests: List<Any?>,
    val reconciliationManifestHash: String,
    val resultHash: String,
    val expiresAt: Instant,
    val resultDeliveryPolicyVersion: String,
    val contentEncoding: String,
)
