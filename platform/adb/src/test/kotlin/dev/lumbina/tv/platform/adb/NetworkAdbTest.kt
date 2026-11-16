package dev.lumbina.tv.platform.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkAdbTest {

    @Test
    fun `the device's address is its first that isn't the loopback`() {
        val output = """
            1: lo    inet 127.0.0.1/8 scope host lo\       valid_lft forever preferred_lft forever
            23: wlan0    inet 192.168.18.73/24 brd 192.168.18.255 scope global wlan0\       valid_lft forever
        """.trimIndent()
        assertEquals("192.168.18.73", addressIn(output))
    }

    @Test
    fun `no network is no address`() {
        assertNull(addressIn("1: lo    inet 127.0.0.1/8 scope host lo"))
    }
}
