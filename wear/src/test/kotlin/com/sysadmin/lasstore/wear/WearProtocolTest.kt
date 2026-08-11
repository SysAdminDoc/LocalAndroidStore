package com.sysadmin.lasstore.wear

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WearProtocolTest {
    @Test
    fun updateCountPayloadsAreNonNegativeAndRoundTrip() {
        assertArrayEquals("0".toByteArray(), WearProtocol.encodeUpdateCount(-3))
        assertEquals(42, WearProtocol.decodeUpdateCount(WearProtocol.encodeUpdateCount(42)))
    }

    @Test
    fun invalidUpdateCountPayloadIsIgnored() {
        assertNull(WearProtocol.decodeUpdateCount("not-a-count".toByteArray()))
    }
}
