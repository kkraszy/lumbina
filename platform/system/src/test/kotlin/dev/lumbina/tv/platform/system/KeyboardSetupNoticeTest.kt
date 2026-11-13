package dev.lumbina.tv.platform.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardSetupNoticeTest {

    @Test
    fun `Android's keyboard setup notice is left out, other notices of the system and apps are not`() {
        assertTrue(isKeyboardSetup("android", "PHYSICAL_KEYBOARD"))
        assertFalse(isKeyboardSetup("android", "DEVELOPER"))
        assertFalse(isKeyboardSetup("com.some.app", "PHYSICAL_KEYBOARD"))
        assertFalse(isKeyboardSetup("android", null))
    }
}
