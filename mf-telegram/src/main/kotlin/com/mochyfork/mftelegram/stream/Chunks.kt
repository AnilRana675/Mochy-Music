// sparse per-file storage for downloaded telegram bytes. a file is one sparse file on disk
// plus a bitset of covered 1 mb chunks. tdlib downloads come in aligned chunks, so no segment
// bookkeeping is needed beyond a bitmap; the bitmap persists so cold start resumes playback
package com.mochyfork.mftelegram.stream

import java.io.File
import java.io.RandomAccessFile
import java.util.BitSet

class ChunkStore(root: File, val chunkSize: Int = DefaultChunkSize) {
    // tdlib caps downloadFile limits at 1 mib, so one tdlib request == one chunk
    companion object { const val DefaultChunkSize = 1024 * 1024 }

    private val dir = root.resolve("chunks").apply { mkdirs() }
    private val open = HashMap<String, FileChunks>()

    @Synchronized
    fun obtain(uniqueId: String): FileChunks =
        open.getOrPut(uniqueId) {
            FileChunks(dir.resolve(uniqueId), dir.resolve("$uniqueId.chunks"), chunkSize)
        }

    @Synchronized
    fun wipe(uniqueId: String) {
        open.remove(uniqueId)?.closeWithDelete()
        dir.resolve(uniqueId).delete()
        dir.resolve("$uniqueId.chunks").delete()
    }

    @Synchronized
    fun wipeAll() {
        open.keys.toList().forEach(::wipe)
    }
}

class FileChunks(
    private val dataFile: File,
    private val sidecarFile: File,
    private val chunkSize: Int,
) {
    private val data = RandomAccessFile(dataFile, "rw")
    private val covered: BitSet = load()
    private val sidecar = RandomAccessFile(sidecarFile, "rw")

    val downloadedBytes: Long get() = covered.cardinality() * chunkSize.toLong()

    /** is [from, untilExclusive) fully local yet? the cheap check the swap controller uses */
    fun covers(from: Long, untilExclusive: Long): Boolean {
        if (untilExclusive <= from) return true
        val first = (from / chunkSize).toInt()
        val last = ((untilExclusive - 1) / chunkSize).toInt()
        for (i in first..last) if (!covered.get(i)) return false
        return true
    }

    /** read up to maxBytes at offset, stopping at chunk edges. null = chunk not covered yet */
    fun read(offset: Long, maxBytes: Int): ByteArray? {
        val index = (offset / chunkSize).toInt()
        if (!covered.get(index)) return null
        val within = (offset % chunkSize).toInt()
        val availableInChunk = chunkSize - within
        val len = minOf(maxBytes, availableInChunk)
        val buf = ByteArray(len)
        synchronized(this) {
            data.seek(offset)
            data.readFully(buf)
        }
        return buf
    }

    /** store one chunk. callers must write chunk-aligned; the fake in tests does the same */
    fun writeChunk(index: Int, bytes: ByteArray) {
        synchronized(this) {
            data.seek(index.toLong() * chunkSize)
            data.write(bytes)
            if (!covered.get(index)) {
                covered.set(index)
                persist()
            }
        }
    }

    // ~600k chunks = years of audio; the text sidecar stays tiny
    private fun persist() {
        synchronized(this) {
            sidecar.seek(0)
            sidecar.setLength(0)
            val sb = StringBuilder()
            covered.stream().forEach { i -> sb.append(i).append(',') }
            sidecar.writeBytes(sb.trimEnd(',').toString())
        }
    }

    private fun load(): BitSet {
        val set = BitSet()
        if (!sidecarFile.exists()) return set
        sidecarFile.bufferedReader().use { text ->
            text.readLine().orEmpty().split(',').forEach { i -> i.toIntOrNull()?.let(set::set) }
        }
        return set
    }

    fun close() {
        runCatching { data.close() }
        runCatching { sidecar.close() }
    }

    fun closeWithDelete() {
        close()
        dataFile.delete()
        sidecarFile.delete()
    }
}
