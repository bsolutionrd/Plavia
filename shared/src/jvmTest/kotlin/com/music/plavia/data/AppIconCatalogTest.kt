package com.music.plavia.data

import kotlin.test.Test
import kotlin.test.assertEquals

class AppIconCatalogTest {
    @Test
    fun darkifyColorIsEnabledBeforeTheOtherAliasesAreDisabled() {
        val aliases = listOf("LauncherDefault", AppIconCatalog.DARKIFY_COLOR, "LauncherPixelNeonViolet")
        val (enable, disable) = AppIconCatalog.enableThenDisable(aliases, AppIconCatalog.DARKIFY_COLOR)
        assertEquals(AppIconCatalog.DARKIFY_COLOR, enable)
        assertEquals(listOf("LauncherDefault", "LauncherPixelNeonViolet"), disable)
    }
}
