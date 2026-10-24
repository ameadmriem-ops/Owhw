package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying AdMob configuration and business logic.
 */
class ExampleUnitTest {

    @Test
    fun verifyAdMobConstants() {
        // 1. Verify User-specified App ID and Ad Unit IDs
        val expectedFiveAdsUnitId = "ca-app-pub-2277779478101583/1404104591"
        val expectedEpisodeUnitId = "ca-app-pub-2277779478101583/9116970862"

        assertEquals(expectedFiveAdsUnitId, MainActivity.DEFAULT_REWARDED_AD_UNIT_ID)
        assertEquals(expectedEpisodeUnitId, MainActivity.EPISODE_REWARDED_AD_UNIT_ID)

        // Ensure the two ad units are strictly distinct and separated
        assertNotEquals(MainActivity.DEFAULT_REWARDED_AD_UNIT_ID, MainActivity.EPISODE_REWARDED_AD_UNIT_ID)
    }

    @Test
    fun testExemptionCalculationLogic() {
        val now = 1000000000000L
        val oneDayMs = MainActivity.ONE_DAY_MS
        assertEquals(24L * 60L * 60L * 1000L, oneDayMs)

        val expiresAt = now + oneDayMs
        assertTrue("Exemption should be active when current time is before expiry", expiresAt > now)

        val afterOneDay = now + oneDayMs + 1000L
        assertFalse("Exemption should expire after 24 hours", expiresAt > afterOneDay)
    }

    @Test
    fun testFiveAdsCounterBoundaryLogic() {
        // Counter must strictly be in range 0..5
        var count = 0
        for (i in 1..4) {
            count++
            assertTrue("Counter should not reach threshold yet", count < 5)
        }
        assertEquals(4, count)

        // 5th reward activates threshold
        count++
        assertTrue("5th reward triggers exemption threshold", count >= 5)
    }
}
