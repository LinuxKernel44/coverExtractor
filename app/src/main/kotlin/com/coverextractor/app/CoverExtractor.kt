package com.coverextractor.app

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.ByteArrayOutputStream

/** A cover successfully pulled out of an audio file, as the exact bytes stored inside it. */
data class ExtractedCover(val bytes: ByteArray, val mimeType: String, val extension: String)

sealed interface CoverResult {
    data class Found(val cover: ExtractedCover) : CoverResult
    object NotFound : CoverResult
    data class Failed(val message: String) : CoverResult
}

/**
 * Pulls the embedded cover art out of an audio file without ever re-encoding it: every code path
 * below returns the exact original image bytes as they were stored in the file, so the picture
 * that lands in the gallery is bit-for-bit identical to the artwork the tagger embedded.
 */
object CoverExtractor {

    fun extract(context: Context, uri: Uri): CoverResult {
        readWithMediaMetadataRetriever(context, uri)?.let { return CoverResult.Found(it) }
        return extractFallbackOnly(context, uri)
    }

    /**
     * Same fallback binary parsers as [extract], without the initial `MediaMetadataRetriever`
     * attempt. Exposed so callers who already opened their own `MediaMetadataRetriever` (e.g. to
     * read tags) can try its `embeddedPicture` first and only pay for a full byte read here if
     * that came back empty, instead of instantiating a second retriever.
     */
    fun extractFallbackOnly(context: Context, uri: Uri): CoverResult {
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return CoverResult.Failed("Could not open file")
        } catch (e: Exception) {
            return CoverResult.Failed(e.message ?: "Could not read file")
        }

        val raw = try {
            findEmbeddedPicture(bytes)
        } catch (e: Exception) {
            null
        } ?: return CoverResult.NotFound

        return CoverResult.Found(toExtractedCover(raw.first, raw.second))
    }

    /** Wraps a raw picture (e.g. from a caller's own retriever) into a lossless [ExtractedCover]. */
    fun wrapRawPicture(bytes: ByteArray): ExtractedCover = toExtractedCover(bytes, null)

    private fun readWithMediaMetadataRetriever(context: Context, uri: Uri): ExtractedCover? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val picture = retriever.embeddedPicture ?: return null
            toExtractedCover(picture, null)
        } catch (e: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun toExtractedCover(bytes: ByteArray, declaredMime: String?): ExtractedCover {
        val sniffed = sniffImageMime(bytes)
        val mime = sniffed ?: declaredMime?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
        val extension = extensionForMime(mime)
        return ExtractedCover(bytes, mime, extension)
    }

    private fun extensionForMime(mime: String): String = when (mime) {
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/bmp" -> "bmp"
        "image/webp" -> "webp"
        else -> "jpg"
    }

    private fun sniffImageMime(b: ByteArray): String? = when {
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "image/jpeg"
        b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() &&
            b[3] == 'G'.code.toByte() -> "image/png"
        b.size >= 6 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() -> "image/gif"
        b.size >= 2 && b[0] == 'B'.code.toByte() && b[1] == 'M'.code.toByte() -> "image/bmp"
        b.size >= 12 && b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() && b[10] == 'B'.code.toByte() &&
            b[11] == 'P'.code.toByte() -> "image/webp"
        else -> null
    }

    /** Tries every container format we understand, in order, purely by sniffing magic bytes. */
    private fun findEmbeddedPicture(data: ByteArray): Pair<ByteArray, String?>? {
        if (has(data, 0, "ID3")) parseId3v2(data)?.let { return it }
        if (has(data, 0, "fLaC")) parseFlac(data)?.let { return it }
        if (data.size >= 12 && has(data, 4, "ftyp")) parseMp4(data)?.let { return it }
        if (has(data, 0, "OggS")) parseOgg(data)?.let { return it }
        if (data.size >= 16 && regionEquals(data, 0, ASF_HEADER_GUID)) parseAsf(data)?.let { return it }
        // APEv2 tags sit at the very end of the file regardless of the container, so try it last
        // as a catch-all fallback (used standalone by Monkey's Audio / WavPack, and sometimes MP3).
        parseApeV2(data)?.let { return it }
        return null
    }

    private fun has(data: ByteArray, offset: Int, ascii: String): Boolean {
        if (offset + ascii.length > data.size) return false
        for (i in ascii.indices) {
            if (data[offset + i] != ascii[i].code.toByte()) return false
        }
        return true
    }

    private fun regionEquals(data: ByteArray, offset: Int, pattern: ByteArray): Boolean {
        if (offset + pattern.size > data.size) return false
        for (i in pattern.indices) {
            if (data[offset + i] != pattern[i]) return false
        }
        return true
    }

    private fun indexOfByte(data: ByteArray, value: Byte, from: Int, to: Int): Int {
        var i = from
        while (i < to) {
            if (data[i] == value) return i
            i++
        }
        return -1
    }

    /** Index of the next 0x00 0x00 pair, used to find the end of a null-terminated UTF-16 string. */
    private fun indexOfUtf16Null(data: ByteArray, from: Int, to: Int): Int {
        var i = from
        while (i + 1 < to) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) return i
            i += 2
        }
        return -1
    }

    private fun u16LE(data: ByteArray, p: Int): Int =
        (data[p].toInt() and 0xFF) or ((data[p + 1].toInt() and 0xFF) shl 8)

    private fun u32LE(data: ByteArray, p: Int): Long =
        (data[p].toLong() and 0xFF) or
            ((data[p + 1].toLong() and 0xFF) shl 8) or
            ((data[p + 2].toLong() and 0xFF) shl 16) or
            ((data[p + 3].toLong() and 0xFF) shl 24)

    private fun u64LE(data: ByteArray, p: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (data[p + i].toLong() and 0xFF)
        return v
    }

    private fun u32BE(data: ByteArray, p: Int): Long =
        ((data[p].toLong() and 0xFF) shl 24) or
            ((data[p + 1].toLong() and 0xFF) shl 16) or
            ((data[p + 2].toLong() and 0xFF) shl 8) or
            (data[p + 3].toLong() and 0xFF)

    private fun u24BE(data: ByteArray, p: Int): Int =
        ((data[p].toInt() and 0xFF) shl 16) or
            ((data[p + 1].toInt() and 0xFF) shl 8) or
            (data[p + 2].toInt() and 0xFF)

    /** ID3v2 4-byte "syncsafe" integer: only the low 7 bits of each byte are significant. */
    private fun syncsafe32(data: ByteArray, p: Int): Int =
        ((data[p].toInt() and 0x7F) shl 21) or
            ((data[p + 1].toInt() and 0x7F) shl 14) or
            ((data[p + 2].toInt() and 0x7F) shl 7) or
            (data[p + 3].toInt() and 0x7F)

    // ---- MP3 / ID3v2 (APIC frame) ----------------------------------------------------------

    private fun parseId3v2(data: ByteArray): Pair<ByteArray, String?>? {
        if (data.size < 10) return null
        val majorVersion = data[3].toInt() and 0xFF
        val flags = data[6].toInt() and 0xFF
        val tagSize = syncsafe32(data, 6)
        val tagEnd = minOf(10 + tagSize, data.size)
        var pos = 10
        if (majorVersion >= 3 && (flags and 0x40) != 0 && pos + 4 <= data.size) {
            val extSize = if (majorVersion >= 4) syncsafe32(data, pos) else u32BE(data, pos).toInt()
            pos += if (majorVersion >= 4) extSize else extSize + 4
        }

        return if (majorVersion == 2) parseId3v22Pic(data, pos, tagEnd) else parseId3v23Apic(data, pos, tagEnd, majorVersion)
    }

    private fun parseId3v23Apic(data: ByteArray, start: Int, tagEnd: Int, majorVersion: Int): Pair<ByteArray, String?>? {
        var pos = start
        while (pos + 10 <= tagEnd) {
            if (data[pos] == 0.toByte()) break // padding reached
            val frameId = String(data, pos, 4, Charsets.US_ASCII)
            val frameSize = if (majorVersion >= 4) syncsafe32(data, pos + 4) else u32BE(data, pos + 4).toInt()
            val frameStart = pos + 10
            val frameEnd = frameStart + frameSize
            if (frameSize < 0 || frameEnd > tagEnd || frameEnd > data.size) break
            if (frameId == "APIC") {
                parseApicPayload(data, frameStart, frameEnd)?.let { return it }
            }
            pos = frameEnd
        }
        return null
    }

    private fun parseApicPayload(data: ByteArray, start: Int, end: Int): Pair<ByteArray, String?>? {
        var p = start
        if (p >= end) return null
        val encoding = data[p].toInt() and 0xFF
        p += 1
        val mimeEnd = indexOfByte(data, 0, p, end)
        if (mimeEnd < 0) return null
        val mime = String(data, p, mimeEnd - p, Charsets.ISO_8859_1)
        p = mimeEnd + 1
        p += 1 // picture type byte
        if (p >= end) return null
        val descEnd = if (encoding == 1 || encoding == 2) indexOfUtf16Null(data, p, end) else indexOfByte(data, 0, p, end)
        if (descEnd < 0) return null
        p = descEnd + if (encoding == 1 || encoding == 2) 2 else 1
        if (p > end) return null
        return data.copyOfRange(p, end) to mime.ifBlank { null }
    }

    /** ID3v2.2 uses 3-character frame IDs, 6-byte frame headers, and a "PIC" frame instead of APIC. */
    private fun parseId3v22Pic(data: ByteArray, start: Int, tagEnd: Int): Pair<ByteArray, String?>? {
        var pos = start
        while (pos + 6 <= tagEnd) {
            if (data[pos] == 0.toByte()) break // padding reached
            val frameId = String(data, pos, 3, Charsets.US_ASCII)
            val frameSize = u24BE(data, pos + 3)
            val frameStart = pos + 6
            val frameEnd = frameStart + frameSize
            if (frameSize < 0 || frameEnd > tagEnd || frameEnd > data.size) break
            if (frameId == "PIC") {
                var p = frameStart
                if (p + 5 <= frameEnd) {
                    val format = String(data, p + 1, 3, Charsets.US_ASCII)
                    p += 4 // encoding(1) + format(3)
                    p += 1 // picture type
                    val descEnd = indexOfByte(data, 0, p, frameEnd)
                    if (descEnd >= 0) {
                        val imgStart = descEnd + 1
                        val mime = when (format.uppercase()) {
                            "PNG" -> "image/png"
                            "JPG" -> "image/jpeg"
                            else -> null
                        }
                        if (imgStart <= frameEnd) return data.copyOfRange(imgStart, frameEnd) to mime
                    }
                }
            }
            pos = frameEnd
        }
        return null
    }

    // ---- FLAC (METADATA_BLOCK_PICTURE) ------------------------------------------------------

    private fun parseFlac(data: ByteArray): Pair<ByteArray, String?>? {
        var pos = 4
        while (pos + 4 <= data.size) {
            val header = data[pos].toInt() and 0xFF
            val isLast = (header and 0x80) != 0
            val blockType = header and 0x7F
            val length = u24BE(data, pos + 1)
            val blockStart = pos + 4
            val blockEnd = blockStart + length
            if (length < 0 || blockEnd > data.size) break
            if (blockType == 6) parseFlacPictureBlock(data, blockStart, blockEnd)?.let { return it }
            if (isLast) break
            pos = blockEnd
        }
        return null
    }

    private fun parseFlacPictureBlock(data: ByteArray, start: Int, end: Int): Pair<ByteArray, String?>? {
        var p = start + 4 // skip picture type
        if (p + 4 > end) return null
        val mimeLen = u32BE(data, p).toInt(); p += 4
        if (p + mimeLen > end) return null
        val mime = String(data, p, mimeLen, Charsets.US_ASCII); p += mimeLen
        if (p + 4 > end) return null
        val descLen = u32BE(data, p).toInt(); p += 4
        p += descLen
        p += 16 // width, height, depth, colors (4 bytes each)
        if (p + 4 > end) return null
        val dataLen = u32BE(data, p).toInt(); p += 4
        if (dataLen < 0 || p + dataLen > end) return null
        return data.copyOfRange(p, p + dataLen) to mime.ifBlank { null }
    }

    // ---- MP4 / M4A ('covr' atom) -----------------------------------------------------------

    private fun mp4FindBox(data: ByteArray, start: Int, end: Int, type: String): Pair<Int, Int>? {
        var pos = start
        while (pos + 8 <= end) {
            var boxSize = u32BE(data, pos)
            val boxType = String(data, pos + 4, 4, Charsets.US_ASCII)
            var headerSize = 8
            if (boxSize == 1L) {
                if (pos + 16 > end) return null
                boxSize = u64BE(data, pos + 8)
                headerSize = 16
            } else if (boxSize == 0L) {
                boxSize = (end - pos).toLong()
            }
            if (boxSize < headerSize) return null
            val boxEnd = pos + boxSize
            if (boxEnd > end) return null
            if (boxType == type) return pos + headerSize to boxEnd.toInt()
            pos = boxEnd.toInt()
        }
        return null
    }

    private fun u64BE(data: ByteArray, p: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (data[p + i].toLong() and 0xFF)
        return v
    }

    private fun parseMp4(data: ByteArray): Pair<ByteArray, String?>? {
        val moov = mp4FindBox(data, 0, data.size, "moov") ?: return null
        val udta = mp4FindBox(data, moov.first, moov.second, "udta") ?: return null
        val meta = mp4FindBox(data, udta.first, udta.second, "meta") ?: return null
        val metaContentStart = meta.first + 4 // meta is a full box: skip version+flags
        if (metaContentStart > meta.second) return null
        val ilst = mp4FindBox(data, metaContentStart, meta.second, "ilst") ?: return null
        val covr = mp4FindBox(data, ilst.first, ilst.second, "covr") ?: return null
        val dataBox = mp4FindBox(data, covr.first, covr.second, "data") ?: return null
        val (dStart, dEnd) = dataBox
        if (dEnd - dStart < 8) return null
        val typeIndicator = u32BE(data, dStart).toInt()
        val imgStart = dStart + 8
        val mime = when (typeIndicator) {
            13 -> "image/jpeg"
            14 -> "image/png"
            27 -> "image/bmp"
            else -> null
        }
        return data.copyOfRange(imgStart, dEnd) to mime
    }

    // ---- Ogg (Vorbis / Opus METADATA_BLOCK_PICTURE comment) --------------------------------

    private fun parseOgg(data: ByteArray): Pair<ByteArray, String?>? {
        var pos = 0
        var targetSerial: Long? = null
        val packets = mutableListOf<ByteArray>()
        var current = ByteArrayOutputStream()

        while (pos + 27 <= data.size && packets.size < 2) {
            if (!has(data, pos, "OggS")) break
            val serial = u32LE(data, pos + 14)
            val pageSegments = data[pos + 26].toInt() and 0xFF
            val segTableStart = pos + 27
            if (segTableStart + pageSegments > data.size) break
            val segTable = IntArray(pageSegments) { data[segTableStart + it].toInt() and 0xFF }
            var payloadPos = segTableStart + pageSegments

            if (targetSerial == null) targetSerial = serial
            if (serial == targetSerial) {
                var i = 0
                while (i < segTable.size) {
                    val segLen = segTable[i]
                    val chunkEnd = payloadPos + segLen
                    if (chunkEnd > data.size) return null
                    current.write(data, payloadPos, segLen)
                    payloadPos = chunkEnd
                    if (segLen < 255) {
                        packets.add(current.toByteArray())
                        current = ByteArrayOutputStream()
                        if (packets.size >= 2) break
                    }
                    i++
                }
            } else {
                payloadPos += segTable.sum()
            }
            pos = payloadPos
        }

        if (packets.size < 2) return null
        return parseVorbisCommentPacket(packets[1])
    }

    private fun parseVorbisCommentPacket(packet: ByteArray): Pair<ByteArray, String?>? {
        // Skip the packet type/codec-identifier header ("\x03vorbis" or "OpusTags").
        var p = when {
            packet.size >= 7 && String(packet, 1, 6, Charsets.US_ASCII) == "vorbis" -> 7
            packet.size >= 8 && String(packet, 0, 8, Charsets.US_ASCII) == "OpusTags" -> 8
            else -> return null
        }
        if (p + 4 > packet.size) return null
        val vendorLen = u32LE(packet, p).toInt(); p += 4 + vendorLen
        if (p + 4 > packet.size) return null
        val commentCount = u32LE(packet, p).toInt(); p += 4
        repeat(commentCount) {
            if (p + 4 > packet.size) return null
            val len = u32LE(packet, p).toInt(); p += 4
            if (len < 0 || p + len > packet.size) return null
            val comment = String(packet, p, len, Charsets.UTF_8)
            p += len
            val eq = comment.indexOf('=')
            if (eq > 0) {
                val key = comment.substring(0, eq).uppercase()
                val value = comment.substring(eq + 1)
                if (key == "METADATA_BLOCK_PICTURE") {
                    val decoded = try {
                        android.util.Base64.decode(value, android.util.Base64.DEFAULT)
                    } catch (e: Exception) {
                        null
                    }
                    if (decoded != null) parseFlacPictureBlock(decoded, 4, decoded.size)?.let { return it }
                } else if (key == "COVERART") {
                    val decoded = try {
                        android.util.Base64.decode(value, android.util.Base64.DEFAULT)
                    } catch (e: Exception) {
                        null
                    }
                    if (decoded != null) return decoded to null
                }
            }
        }
        return null
    }

    // ---- WMA / ASF (WM/Picture extended content descriptor) --------------------------------

    private val ASF_HEADER_GUID = byteArrayOf(
        0x30, 0x26, 0xB2.toByte(), 0x75, 0x8E.toByte(), 0x66, 0xCF.toByte(), 0x11,
        0xA6.toByte(), 0xD9.toByte(), 0x00, 0xAA.toByte(), 0x00, 0x62, 0xCE.toByte(), 0x6C
    )
    private val ASF_EXT_CONTENT_DESC_GUID = byteArrayOf(
        0x40, 0xA4.toByte(), 0xD0.toByte(), 0xD2.toByte(), 0x07, 0xE3.toByte(), 0xD2.toByte(), 0x11,
        0x97.toByte(), 0xF0.toByte(), 0x00, 0xA0.toByte(), 0xC9.toByte(), 0x5E, 0xA8.toByte(), 0x50
    )

    private fun parseAsf(data: ByteArray): Pair<ByteArray, String?>? {
        if (data.size < 30) return null
        val headerSize = u64LE(data, 16)
        val headerEnd = minOf(headerSize, data.size.toLong()).toInt()
        var pos = 30
        while (pos + 24 <= headerEnd) {
            val objGuid = data.copyOfRange(pos, pos + 16)
            val objSize = u64LE(data, pos + 16)
            if (objSize < 24) break
            val objEnd = pos + objSize
            if (objEnd > data.size) break
            if (objGuid.contentEquals(ASF_EXT_CONTENT_DESC_GUID)) {
                parseAsfExtContentDesc(data, pos + 24, objEnd.toInt())?.let { return it }
            }
            pos = objEnd.toInt()
        }
        return null
    }

    private fun parseAsfExtContentDesc(data: ByteArray, start: Int, end: Int): Pair<ByteArray, String?>? {
        var p = start
        if (p + 2 > end) return null
        val count = u16LE(data, p); p += 2
        repeat(count) {
            if (p + 2 > end) return null
            val nameLen = u16LE(data, p); p += 2
            if (p + nameLen > end) return null
            val name = String(data, p, nameLen, Charsets.UTF_16LE).trimEnd('\u0000')
            p += nameLen
            if (p + 4 > end) return null
            val valType = u16LE(data, p); p += 2
            val valLen = u16LE(data, p); p += 2
            if (p + valLen > end) return null
            val valStart = p
            val valEnd = p + valLen
            if (name == "WM/Picture" && valType == 1) {
                parseWmPicture(data, valStart, valEnd)?.let { return it }
            }
            p = valEnd
        }
        return null
    }

    private fun parseWmPicture(data: ByteArray, start: Int, end: Int): Pair<ByteArray, String?>? {
        var p = start
        if (p + 5 > end) return null
        p += 1 // picture type
        val dataLen = u32LE(data, p).toInt(); p += 4
        val mimeEnd = indexOfUtf16Null(data, p, end)
        if (mimeEnd < 0) return null
        val mime = String(data, p, mimeEnd - p, Charsets.UTF_16LE)
        p = mimeEnd + 2
        val descEnd = indexOfUtf16Null(data, p, end)
        if (descEnd < 0) return null
        p = descEnd + 2
        if (dataLen < 0 || p + dataLen > end) return null
        return data.copyOfRange(p, p + dataLen) to mime.ifBlank { null }
    }

    // ---- APEv2 (Monkey's Audio / WavPack / MP3 fallback) -----------------------------------

    private fun parseApeV2(data: ByteArray): Pair<ByteArray, String?>? {
        if (data.size < 32) return null
        val footerStart = data.size - 32
        if (!has(data, footerStart, "APETAGEX")) return null
        val tagSize = u32LE(data, footerStart + 12).toInt()
        val itemsStart = footerStart - (tagSize - 32)
        if (itemsStart < 0) return null
        var p = itemsStart
        val itemsEnd = footerStart
        while (p + 8 <= itemsEnd) {
            val valueSize = u32LE(data, p).toInt(); p += 4
            val flags = u32LE(data, p).toInt(); p += 4
            val keyEnd = indexOfByte(data, 0, p, itemsEnd)
            if (keyEnd < 0) break
            val key = String(data, p, keyEnd - p, Charsets.US_ASCII)
            p = keyEnd + 1
            val valEnd = p + valueSize
            if (valueSize < 0 || valEnd > itemsEnd) break
            val isBinary = ((flags shr 1) and 0x3) == 1
            if (isBinary && (key.equals("Cover Art (Front)", true) || key.equals("Cover Art (Back)", true))) {
                val descEnd = indexOfByte(data, 0, p, valEnd)
                val imgStart = if (descEnd in p until valEnd) descEnd + 1 else p
                return data.copyOfRange(imgStart, valEnd) to null
            }
            p = valEnd
        }
        return null
    }
}
