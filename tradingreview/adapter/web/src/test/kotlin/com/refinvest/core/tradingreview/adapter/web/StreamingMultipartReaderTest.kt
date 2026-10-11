package com.refinvest.core.tradingreview.adapter.web

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class StreamingMultipartReaderTest {
    @Test
    fun readsTwoBoundedPartsWithoutBufferingTheFile() {
        val boundary = "boundary-opaque"
        val metadata = "{\"role\":\"TRADE_HISTORY\"}"
        val file = "alpha\r\n--boundary-opaqueXbad\r\nomega".toByteArray()
        val prefix = "--$boundary\r\nContent-Disposition: form-data; name=\"metadata\"\r\nContent-Type: application/json\r\n\r\n$metadata\r\n--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"ignored.csv\"\r\nContent-Type: text/csv\r\n\r\n".toByteArray()
        val suffix = "\r\n--$boundary--\r\n".toByteArray()
        val reader = StreamingMultipartReader(ByteArrayInputStream(prefix + file + suffix),
            "multipart/form-data; boundary=$boundary")
        assertEquals(metadata, reader.nextPart()!!.body.readAllBytes().toString(Charsets.UTF_8))
        val part = reader.nextPart()!!
        assertEquals("file", part.name)
        assertContentEquals(file, part.body.readAllBytes())
        assertNull(reader.nextPart())
    }

    @Test
    fun rejectsTruncatedBody() {
        val body = "--x\r\nContent-Disposition: form-data; name=\"file\"\r\n\r\nbytes".toByteArray()
        val reader = StreamingMultipartReader(ByteArrayInputStream(body), "multipart/form-data; boundary=x")
        assertFailsWith<Exception> { reader.nextPart()!!.body.readAllBytes() }
    }
}
