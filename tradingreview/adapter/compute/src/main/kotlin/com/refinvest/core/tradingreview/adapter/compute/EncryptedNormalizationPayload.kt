package com.refinvest.core.tradingreview.adapter.compute

import com.refinvest.core.tradingreview.port.normalization.result.NormalizationPayloadFile
import com.refinvest.core.tradingreview.port.deletion.TradingArtifactLease
import com.refinvest.core.tradingreview.port.deletion.TradingTemporaryArtifact
import java.io.FilterInputStream
import java.io.InputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Own the workspace lease through parsing, not just through the HTTP download. */
internal class EncryptedNormalizationPayload(
    private val file: TradingTemporaryArtifact,
    private val lease: TradingArtifactLease,
    override val byteSize: Long,
    override val sha256: String,
    private val key: ByteArray,
    private val iv: ByteArray,
) : NormalizationPayloadFile {
    private val readers = mutableSetOf<InputStream>()
    private var closed = false

    @Synchronized
    override fun open(): InputStream {
        check(!closed)
        lease.checkActive()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val reader = object : FilterInputStream(CipherInputStream(file.openInput(), cipher)) {
            override fun read(): Int { lease.checkActive(); return super.read() }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                lease.checkActive(); return super.read(bytes, offset, length)
            }
            override fun close() {
                try { super.close() }
                finally { synchronized(this@EncryptedNormalizationPayload) { readers.remove(this) } }
            }
        }
        readers.add(reader)
        return reader
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            var failure: Exception? = null
            for (reader in readers.toList()) {
                try { reader.close() } catch (error: Exception) { failure = error }
            }
            try { file.close() } catch (error: Exception) { failure = error }
            failure?.let { throw it }
        }
        finally {
            key.fill(0)
            iv.fill(0)
            lease.close()
        }
    }
}
