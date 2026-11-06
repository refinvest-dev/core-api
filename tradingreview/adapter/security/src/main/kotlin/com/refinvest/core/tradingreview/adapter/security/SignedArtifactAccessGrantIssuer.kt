package com.refinvest.core.tradingreview.adapter.security

import com.refinvest.core.tradingreview.domain.importsession.NormalizationArtifactInput
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportSessionId
import com.refinvest.core.tradingreview.port.common.failure.TradingFailure
import com.refinvest.core.tradingreview.port.common.failure.TradingFailureKind
import com.refinvest.core.tradingreview.port.outbound.artifactgrant.ArtifactAccessGrantIssuer
import com.refinvest.core.tradingreview.port.outbound.artifactgrant.ArtifactGrantBinding
import com.refinvest.core.tradingreview.port.outbound.artifactgrant.ArtifactGrantTokens
import com.refinvest.core.tradingreview.port.outbound.artifactgrant.ArtifactReadGrant
import com.refinvest.core.tradingreview.port.outbound.persistence.artifactgrant.ArtifactGrantIssueRecord
import com.refinvest.core.tradingreview.port.outbound.persistence.artifactgrant.ArtifactGrantIssueStore
import com.refinvest.core.tradingreview.port.outbound.storage.raw.RawArtifactStorage
import java.time.Clock
import java.time.Duration
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** Composes credential issuance through independent persistence, signing and storage capabilities. */
@Component
class SignedArtifactAccessGrantIssuer(
    private val grants: ArtifactGrantIssueStore,
    private val tokens: ArtifactGrantTokens,
    private val storage: RawArtifactStorage,
    private val clock: Clock,
) : ArtifactAccessGrantIssuer {
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun issueReadGrant(artifact: NormalizationArtifactInput, logicalJobId: String,
                                sessionId: TradingImportSessionId, attemptToken: String,
                                deletionGeneration: Long): ArtifactReadGrant {
        tokens.requireIssuanceAvailable()
        val binding = ArtifactGrantBinding(artifact, logicalJobId, sessionId, attemptToken, deletionGeneration)
        val source = grants.loadEligible(binding, clock.instant()) ?: unavailable()
        if (source.byteSize != artifact.contentLength || source.sha256 != artifact.sha256 ||
            source.mediaType != artifact.mediaType || source.role != artifact.role.name)
            throw TradingFailure("ARTIFACT_IDENTITY_CONFLICT", TradingFailureKind.CONFLICT)
        val objectKey = source.objectKey ?: unavailable()
        if (!storage.exists(objectKey)) unavailable()
        val credential = tokens.issue()
        val now = clock.instant()
        val expiry = now.plus(Duration.ofMinutes(5))
        if (!grants.recordIfEligible(ArtifactGrantIssueRecord(binding, objectKey, credential.hash, now, expiry)))
            unavailable()
        return ArtifactReadGrant(artifact.artifactId.value.toString(), artifact.role.name, artifact.sha256,
            artifact.contentLength, artifact.mediaType, credential.accessUri, expiry)
    }

    private fun unavailable(): Nothing =
        throw TradingFailure("SOURCE_ARTIFACT_EXPIRED", TradingFailureKind.NO_LONGER_AVAILABLE)
}
