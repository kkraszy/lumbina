package dev.lumbina.tv.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardEditingTest {

    @Test
    fun `typing honours shift`() {
        assertEquals("Д", KeyboardEditing.type("", 'д', shift = true))
        assertEquals("Дa", KeyboardEditing.type("Д", 'a', shift = false))
    }

    @Test
    fun `no leading or doubled spaces`() {
        assertEquals("", KeyboardEditing.space(""))
        assertEquals("a ", KeyboardEditing.space("a"))
        assertEquals("a ", KeyboardEditing.space("a "))
    }

    @Test
    fun `backspace on empty text stays empty`() {
        assertEquals("", KeyboardEditing.backspace(""))
        assertEquals("ab", KeyboardEditing.backspace("abc"))
    }

    @Test
    fun `layouts cycle between English and the language's own`() {
        assertEquals(KeyboardLanguage.Russian, KeyboardLanguage.English.next("ru"))
        assertEquals(KeyboardLanguage.English, KeyboardLanguage.Russian.next("ru"))
        assertEquals(KeyboardLanguage.Polish, KeyboardLanguage.English.next("pl"))
        assertEquals(KeyboardLanguage.English, KeyboardLanguage.Polish.next("pl"))
    }
}
