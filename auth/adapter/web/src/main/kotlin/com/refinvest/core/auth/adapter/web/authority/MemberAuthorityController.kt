package com.refinvest.core.auth.adapter.web.authority

import com.refinvest.core.auth.port.inbound.authority.AuthenticateAuthorityCallerUseCase
import com.refinvest.core.auth.port.inbound.authority.CheckMemberAuthorityCommand
import com.refinvest.core.auth.port.inbound.authority.CheckMemberAuthorityUseCase
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Conditional
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.core.JsonToken
import tools.jackson.core.StreamReadConstraints
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind.ObjectMapper
import java.nio.ByteBuffer
import java.util.UUID

@RestController
@Conditional(MemberAuthorityAvailableCondition::class)
class MemberAuthorityController(private val check: CheckMemberAuthorityUseCase, private val mapper: ObjectMapper) {
    private val json = JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(1).maxStringLength(4096).maxNameLength(64).maxTokenCount(16).build()).build()

    @PostMapping(PATH)
    fun check(request: HttpServletRequest, response: HttpServletResponse) {
        val command = try {
            read(request)
        } catch (_: Exception) {
            error(response, 400, "INVALID_REQUEST", false)
            return
        }
        val result = try {
            check.execute(command)
        } catch (_: Exception) {
            error(response, 503, "AUTHORITY_UNAVAILABLE", true)
            return
        }

        val body = linkedMapOf<String, Any>("active" to result.active, "checkId" to result.checkId,
            "tokenSha256" to result.tokenSha256, "checkedAt" to result.checkedAt.toString())
        result.identity?.let { identity ->
            body.putAll(mapOf("sub" to identity.subject, "actor" to identity.actor, "iss" to identity.issuer,
                "aud" to identity.audience, "scope" to identity.scope, "jti" to identity.tokenId,
                "exp" to identity.expiresAt, "policyRevision" to identity.policyRevision.toString()))
        }
        val bytes = mapper.writeValueAsBytes(body)
        if (bytes.size > 4096) {
            error(response, 503, "AUTHORITY_UNAVAILABLE", true)
            return
        }
        response.status = 200
        response.contentType = "application/json"
        response.setHeader("Cache-Control", "no-store")
        response.outputStream.write(bytes)
    }

    private fun read(request: HttpServletRequest): CheckMemberAuthorityCommand {
        require(request.contentLengthLong <= 8192 && request.queryString == null)
        require(request.getHeaders("Content-Type").toList().size == 1)
        val type = MediaType.parseMediaType(requireNotNull(request.contentType))
        require(type.type == "application" && type.subtype == "json" && (type.charset == null || type.charset == Charsets.UTF_8))
        val encodings = request.getHeaders("Content-Encoding").toList()
        require(encodings.size <= 1 && encodings.all { it.equals("identity", true) })
        val bytes = request.inputStream.readNBytes(8193)
        require(bytes.size <= 8192)
        val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        val fields = linkedMapOf<String, String>()
        json.createParser(text).use { parser ->
            require(parser.nextToken() == JsonToken.START_OBJECT)
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                require(parser.currentToken() == JsonToken.PROPERTY_NAME)
                val name = parser.currentName()
                require(name in expectedFields && name !in fields && parser.nextToken() == JsonToken.VALUE_STRING)
                val value = parser.getString()
                require(Charsets.UTF_8.newEncoder().canEncode(value))
                fields[name] = value
            }
            require(parser.nextToken() == null && fields.keys == expectedFields)
        }
        require(fields.getValue("delegation").toByteArray(Charsets.UTF_8).size in 1..4096 &&
            actorPattern.matches(fields.getValue("expectedActor")) && noncePattern.matches(fields.getValue("checkId")))
        return CheckMemberAuthorityCommand(fields.getValue("delegation"), fields.getValue("expectedActor"), fields.getValue("checkId"))
    }

    private fun error(response: HttpServletResponse, status: Int, code: String, retryable: Boolean) {
        response.status = status
        response.contentType = "application/json"
        response.setHeader("Cache-Control", "no-store")
        mapper.writeValue(response.outputStream, mapOf("code" to code, "message" to if (status == 400) "Invalid request" else "Service unavailable",
            "traceId" to UUID.randomUUID().toString(), "retryable" to retryable))
    }

    companion object {
        const val PATH = "/internal/trading-review/v1/delegations/check"
        private val expectedFields = setOf("delegation", "expectedActor", "checkId")
        private val actorPattern = Regex("[a-z][a-z0-9-]{0,63}")
        private val noncePattern = Regex("[0-9a-f]{32}")
    }
}
