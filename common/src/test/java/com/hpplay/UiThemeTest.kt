package com.hpplay

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiThemeTest {
    @Test
    fun autoFollowsTheSystemDayNightMode() {
        assertTrue(resolveDarkMode(UiTheme.AUTO, Configuration.UI_MODE_NIGHT_YES))
        assertFalse(resolveDarkMode(UiTheme.AUTO, Configuration.UI_MODE_NIGHT_NO))
        assertFalse(resolveDarkMode(UiTheme.AUTO, Configuration.UI_MODE_NIGHT_UNDEFINED))
    }

    @Test
    fun explicitChoiceOverridesTheSystem() {
        assertFalse(resolveDarkMode(UiTheme.LIGHT, Configuration.UI_MODE_NIGHT_YES))
        assertTrue(resolveDarkMode(UiTheme.DARK, Configuration.UI_MODE_NIGHT_NO))
    }

    @Test
    fun paletteSwitchesBetweenLightAndDark() {
        val light = SettingsPalette.of(false)
        val dark = SettingsPalette.of(true)
        assertFalse(light.dark)
        assertTrue(dark.dark)
        assertTrue(light.page != dark.page)
        assertTrue(light.accent != dark.accent)
    }

    @Test
    fun unknownStoredNameFallsBackToAuto() {
        assertEquals(UiTheme.AUTO, UiTheme.fromName(null))
        assertEquals(UiTheme.AUTO, UiTheme.fromName("nonsense"))
        assertEquals(UiTheme.DARK, UiTheme.fromName("DARK"))
        assertEquals(UiTheme.LIGHT, UiTheme.fromName("LIGHT"))
    }
}
