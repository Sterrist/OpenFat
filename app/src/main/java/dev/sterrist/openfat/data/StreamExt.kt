package dev.sterrist.openfat.data

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Читает не больше [limit] байт (замена InputStream.readNBytes, которого нет до Android 13). */
fun InputStream.readAtMost(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
    while (out.size() < limit) {
        val read = read(chunk, 0, minOf(chunk.size, limit - out.size()))
        if (read < 0) break
        out.write(chunk, 0, read)
    }
    return out.toByteArray()
}
