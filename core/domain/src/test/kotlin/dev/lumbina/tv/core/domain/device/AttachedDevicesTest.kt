package dev.lumbina.tv.core.domain.device

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AttachedDevicesTest {

    private val stick = AttachedDevice("/dev/bus/usb/001/002", "Stick")

    private class Shell(private val answer: (String) -> String?) : AttachedDevices {
        val commands = mutableListOf<String>()

        override suspend fun list() = emptyList<AttachedDevice>()

        override suspend fun connect(device: AttachedDevice) = AttachedState.Connected

        override suspend fun shell(device: AttachedDevice, command: String): String? {
            commands += command
            return answer(command)
        }
    }

    @Test
    fun `reads the apps from the activity list`() {
        val output = """
            3 activities found:
              Activity #0:
                priority=3 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false
                com.android.tv.settings/.MainSettings
              Activity #1:
                priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false
                com.amazon.amazonvideo.livingroom/com.amazon.ignition.IgnitionActivity
              Activity #2:
                priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false
                com.amazon.amazonvideo.livingroom/com.amazon.Other${'$'}Inner
        """.trimIndent()
        assertEquals(
            listOf(
                AttachedApp("com.android.tv.settings", ".MainSettings"),
                AttachedApp("com.amazon.amazonvideo.livingroom", "com.amazon.ignition.IgnitionActivity"),
            ),
            parseTvApps(output),
        )
    }

    @Test
    fun `no activities is no apps`() {
        assertEquals(emptyList<AttachedApp>(), parseTvApps("No activities found\n"))
    }

    @Test
    fun `not connected has no list`() = runTest {
        assertNull(Shell { null }.tvApps(stick))
    }

    @Test
    fun `launching wakes the device and starts the activity`() = runTest {
        val shell = Shell { "Starting: Intent { cmp=pl.tvp.sport.tv/.presentation.MainActivity }" }
        assertTrue(shell.launch(stick, AttachedApp("pl.tvp.sport.tv", ".presentation.MainActivity")))
        assertEquals(
            listOf("input keyevent KEYCODE_WAKEUP; am start -n 'pl.tvp.sport.tv/.presentation.MainActivity'"),
            shell.commands,
        )
    }

    @Test
    fun `an activity that does not start is a failed launch`() = runTest {
        val shell = Shell { "Error: Activity class {a/.B} does not exist." }
        assertFalse(shell.launch(stick, AttachedApp("a", ".B")))
    }
}
