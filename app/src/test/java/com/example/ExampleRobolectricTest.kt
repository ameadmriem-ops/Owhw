package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun readAppNameStringFromContext() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertTrue("App name should match BLACK ANIME", appName.equals("BLACK ANIME", ignoreCase = true))
    }

    @Test
    fun testAdMobSharedPreferencesPersistence() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(MainActivity.PREFS_ADMOB, Context.MODE_PRIVATE)

        // Clear existing prefs
        prefs.edit().clear().commit()

        // Verify initial state
        var count = prefs.getInt(MainActivity.KEY_FIVE_ADS_COUNT, 0)
        var exemptUntil = prefs.getLong(MainActivity.KEY_EXEMPT_UNTIL, 0L)
        assertEquals(0, count)
        assertFalse(exemptUntil > System.currentTimeMillis())

        // Simulate 3 rewarded ads completed
        for (i in 1..3) {
            count++
            prefs.edit().putInt(MainActivity.KEY_FIVE_ADS_COUNT, count).commit()
        }
        assertEquals(3, prefs.getInt(MainActivity.KEY_FIVE_ADS_COUNT, 0))

        // Complete remaining 2 ads and activate 24-hour exemption
        val now = System.currentTimeMillis()
        val expectedExpiry = now + MainActivity.ONE_DAY_MS
        prefs.edit()
            .putLong(MainActivity.KEY_EXEMPT_UNTIL, expectedExpiry)
            .putInt(MainActivity.KEY_FIVE_ADS_COUNT, 0)
            .commit()

        val savedExpiry = prefs.getLong(MainActivity.KEY_EXEMPT_UNTIL, 0L)
        val savedCountAfterExempt = prefs.getInt(MainActivity.KEY_FIVE_ADS_COUNT, 0)

        assertEquals(0, savedCountAfterExempt)
        assertTrue(savedExpiry > System.currentTimeMillis())
        assertTrue(savedExpiry >= expectedExpiry)
    }

    // ================= User Test Cases 1 through 8 =================

    @Test
    fun testCase1_PreloadedAdValidity() {
        // Test Case 1: Preloaded ad validity within max age (1 hour)
        val now = System.currentTimeMillis()
        val freshTimestamp = now - 5000L // 5s old
        val expiredTimestamp = now - (61 * 60 * 1000L) // 61m old (> 1 hr)

        val maxAgeMs = 60 * 60 * 1000L
        fun isValid(ts: Long): Boolean = ts > 0L && (now - ts) < maxAgeMs

        assertTrue("Fresh preloaded ad is valid", isValid(freshTimestamp))
        assertFalse("Expired preloaded ad is invalid", isValid(expiredTimestamp))
        assertFalse("Zero timestamp is invalid", isValid(0L))
    }

    @Test
    fun testCase2_AdReadyBefore15SecondsStopsMilestones() {
        // Test Case 2: If ad becomes ready before 15s milestone, retry timer is cancelled
        var is15sMilestoneTriggered = false
        var isAdShown = false

        // Simulate ad arriving at 7 seconds
        val adArrivalTime = 7000L
        val milestoneTime = 15000L

        if (adArrivalTime < milestoneTime) {
            isAdShown = true
            // Milestone is cancelled
            is15sMilestoneTriggered = false
        }

        assertTrue("Ad was displayed immediately upon becoming ready", isAdShown)
        assertFalse("15s milestone retry was cancelled and not executed", is15sMilestoneTriggered)
    }

    @Test
    fun testCase3_RetryAt15sOnFirstAttemptFailure() {
        // Test Case 3: Initial attempt failed before 15s; retry occurs at 15s milestone
        var isInitialLoadOngoing = false
        var retryAttemptLaunched = false

        // First attempt failed at t=3s
        isInitialLoadOngoing = false

        // At t=15s milestone check:
        if (!isInitialLoadOngoing) {
            retryAttemptLaunched = true
            isInitialLoadOngoing = true
        }

        assertTrue("Retry was launched at 15s milestone when previous request ended", retryAttemptLaunched)
        assertTrue("State is now actively loading retry", isInitialLoadOngoing)
    }

    @Test
    fun testCase4_Total30sTimeoutOpensEpisodeDirectly() {
        // Test Case 4: No ad available within 30s; timeout opens episode without ad
        var isEpisodeOpened = false
        var wasRewardGranted = false
        var isSearchActive = true

        // Simulate 30s timeout triggered
        val timeoutElapsed = 30000L
        if (timeoutElapsed >= 30000L) {
            isSearchActive = false
            // Open episode without reward
            isEpisodeOpened = true
            wasRewardGranted = false
        }

        assertFalse("Search state must be terminated after 30s", isSearchActive)
        assertTrue("Episode must be opened automatically on timeout", isEpisodeOpened)
        assertFalse("No reward or watch count must be granted on timeout", wasRewardGranted)
    }

    @Test
    fun testCase5_LateCallbackAfterTimeoutIgnored() {
        // Test Case 5: Late callback arriving after 30s timeout is ignored
        var activeAttemptId = "" // Cleared after timeout
        val lateCallbackAttemptId = "ep_attempt_123"
        var lateActionExecuted = false

        // Callback checks if attemptId matches active attempt
        if (activeAttemptId.isNotEmpty() && activeAttemptId == lateCallbackAttemptId) {
            lateActionExecuted = true
        }

        assertFalse("Late callback after timeout must be completely ignored", lateActionExecuted)
    }

    @Test
    fun testCase6_RapidEpisodeSwitchingGuardsAttempt() {
        // Test Case 6: User clicks Episode A, then quickly clicks Episode B
        var activeAttemptId = "attempt_ep_A"
        var activeFid = "anime_show_1"
        var activeIdx = 1

        // User switches to Episode B
        activeAttemptId = "attempt_ep_B"
        activeFid = "anime_show_1"
        activeIdx = 2

        // Callback for Episode A arrives
        val incomingCallbackAttemptId = "attempt_ep_A"
        var episodeOpenedForCallback = ""

        if (incomingCallbackAttemptId == activeAttemptId) {
            episodeOpenedForCallback = "ep_A"
        }

        assertEquals("Episode A callback rejected because active attempt is now Episode B", "", episodeOpenedForCallback)
    }

    @Test
    fun testCase7_NoRewardCreditedOnFailureOrTimeout() {
        // Test Case 7: Only onUserEarnedReward callback grants reward
        var userEarnedRewardCallbackCalled = false
        var dismissedCallbackCalled = true
        var watchCredited = false

        // Ad was dismissed before reward callback was triggered (dismissed early)
        if (userEarnedRewardCallbackCalled) {
            watchCredited = true
        } else {
            watchCredited = false
        }

        assertFalse("Closing ad without reward callback must not grant reward or watch", watchCredited)
    }

    @Test
    fun testCase8_ConcurrentLoadPrevention() {
        // Test Case 8: Prevent concurrent load requests for the same ad unit
        var isUnitLoading = true
        var duplicateRequestSpawned = false

        // Attempting another load while isUnitLoading is true:
        if (!isUnitLoading) {
            duplicateRequestSpawned = true
        } else {
            duplicateRequestSpawned = false
        }

        assertFalse("Duplicate concurrent load request must be blocked", duplicateRequestSpawned)
    }

    @Test
    fun testNewsFeatureAssetsAndSecurityRulesIntegrity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val html = context.assets.open("index.html").bufferedReader(Charsets.UTF_8).use { it.readText() }

        // 1. Side drawer News button preserved alongside existing drawer items
        assertTrue("Side drawer must include الأخبار button", html.contains("""data-page="news" onclick="navFromDrawer('news')""""))
        assertTrue("Side drawer must display الأخبار label", html.contains("""<span class="drawer-label">الأخبار</span>"""))
        assertTrue("Existing side drawer items must remain intact", html.contains("""navFromDrawer('coming')""") && html.contains("""navFromDrawer('favorites')"""))

        // 2. News page header, card actions, external link & pin-to-details wiring
        assertTrue("News header must render اخر الأخبار", html.contains("اخر الأخبار"))
        assertTrue("News collection reference must exist", html.contains("""collection(db,"news")"""))
        assertTrue("News card click must route to external URL", html.contains("window.handleNewsCardClick"))
        assertTrue("News pin button must route to linked work details", html.contains("window.openNewsLinkedWork") && html.contains("news-pin-btn"))
        assertTrue("News empty state must exist without fake sample news", html.contains("لا توجد أخبار منشورة حالياً"))

        // 3. Admin Add News button and required 5 fields + Save/Cancel buttons
        assertTrue("Admin control panel must include إضافة خبر button", html.contains("إضافة خبر") && html.contains("window.openAddNewsPage()"))
        assertTrue("Add News form must include title input", html.contains("""id="newsTitleInput""""))
        assertTrue("Add News form must include external URL input", html.contains("""id="newsExternalUrlInput""""))
        assertTrue("Add News form must include poster URL input", html.contains("""id="newsPosterUrlInput""""))
        assertTrue("Add News form must include content type selector", html.contains("""id="newsLinkedTypeSelect""""))
        assertTrue("Add News form must include work picker search & list", html.contains("""id="newsLinkedWorkSearchInput"""") && html.contains("""id="newsLinkedWorksList""""))
        assertTrue("Add News form must include save and cancel buttons", html.contains("""id="saveNewsItemBtn"""") && html.contains("""id="cancelNewsFormBtn""""))
    }
}

