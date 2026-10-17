package dev.sterrist.openfat.data.repo

import org.junit.Assert.assertEquals
import org.junit.Test

class MaskEmailTest {
    @Test
    fun keepsFirstThreeLettersAndDomain() {
        assertEquals("exa****@mail.ru", SettingsRepository.maskEmail("example@mail.ru"))
        assertEquals("exa****@mail.ru", SettingsRepository.maskEmail("  example@mail.ru "))
    }

    @Test
    fun shortOrMalformedAddresses() {
        assertEquals("ab****@mail.ru", SettingsRepository.maskEmail("ab@mail.ru"))
        assertEquals("use****", SettingsRepository.maskEmail("username"))
    }
}
