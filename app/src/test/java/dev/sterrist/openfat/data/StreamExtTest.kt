package dev.sterrist.openfat.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class StreamExtTest {
    @Test
    fun readsWholeStreamWhenShorterThanLimit() {
        val data = ByteArray(10_000) { it.toByte() }
        assertEquals(data.toList(), ByteArrayInputStream(data).readAtMost(20_000).toList())
    }

    @Test
    fun stopsAtLimit() {
        val data = ByteArray(20_000) { it.toByte() }
        val read = ByteArrayInputStream(data).readAtMost(12_345)
        assertEquals(12_345, read.size)
        assertEquals(data.take(12_345), read.toList())
    }
}
