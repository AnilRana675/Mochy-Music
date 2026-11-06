// chunk store: sparse write/read, coverage, and sidecar persistence across reobtain
package com.mochyfork.mftelegram.stream

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChunkStoreTest {
    @get:Rule var tmp = TemporaryFolder()

    private val cs = 1024

    @Test fun writeAndReadOneChunk() {
        val store = ChunkStore(tmp.root, cs)
        val fc = store.obtain("id")
        val data = ByteArray(64) { it.toByte() }
        fc.writeChunk(0, data)
        assertEquals(data.toList(), fc.read(0, 64)!!.toList())
    }

    @Test fun readStopsAtChunkEdge() {
        val store = ChunkStore(tmp.root, cs)
        val fc = store.obtain("id")
        fc.writeChunk(0, ByteArray(cs))
        assertEquals(cs, fc.read(0, cs * 2)!!.size)
        assertNull(fc.read(cs.toLong(), 10))
    }

    @Test fun uncoveredChunkReadsNull() {
        val store = ChunkStore(tmp.root, cs)
        val fc = store.obtain("id")
        fc.writeChunk(0, ByteArray(cs))
        fc.writeChunk(2, ByteArray(cs))
        assertNull(fc.read(cs.toLong(), 10))
        assertTrue(fc.covers(0, 1024))
        assertFalse(fc.covers(0, 2048))
    }

    @Test fun coverageSurvivesReobtainInSameProcess() {
        val store = ChunkStore(tmp.root, cs)
        store.obtain("id").writeChunk(1, ByteArray(cs))
        store.obtain("id") // same instance returns
        assertTrue(store.obtain("id").covers(1024, 2048))
    }

    @Test fun sidecarPersistsAcrossStoreInstances() {
        ChunkStore(tmp.root, cs).obtain("id").writeChunk(3, ByteArray(cs))
        val again = ChunkStore(tmp.root, cs).obtain("id")
        assertTrue(again.covers(3072, 4096))
        assertEquals(cs.toLong(), again.downloadedBytes)
    }

    @Test fun wipeRemovesEverything() {
        val store = ChunkStore(tmp.root, cs)
        store.obtain("id").writeChunk(0, ByteArray(cs))
        store.wipe("id")
        val fresh = store.obtain("id")
        assertFalse(fresh.covers(0, 1024))
    }
}
