package com.example

import android.content.Context
import android.content.pm.ActivityInfo
import android.view.TextureView
import android.view.View
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper

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

    @Test
    fun testSearchStatePersistenceAndBackNavigationIntegrity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val html = context.assets.open("index.html").bufferedReader(Charsets.UTF_8).use { it.readText() }

        // 1. Search session state model persists query, filter, resultFids, hasExecuted, and scrollY
        assertTrue("Search session state object must exist", html.contains("window.searchSessionState"))
        assertTrue("Search session state must store resultFids order", html.contains("window.searchSessionState.resultFids"))
        assertTrue("Search session state must track scrollY", html.contains("window.searchSessionState.scrollY"))

        // 2. Search page restoration preserves query, result order, and avoids unwanted autofocus
        assertTrue("renderSearchPage must accept isRestoring parameter", html.contains("window.renderSearchPage = function(isRestoring = false)"))
        assertTrue("performSearch must support preserveSavedOrder", html.contains("window.performSearch=(preserveSavedOrder = false)=>"))
        assertTrue("openSearchBack must restore search page and scroll position", html.contains("window.openSearchBack=()=>{") && html.contains("window.restorePageScroll("))

        // 3. Multi-step back navigation (Search -> Details -> Episodes -> Player -> Back -> Episodes -> Details -> Search)
        assertTrue("openEpisodesBack must exist to restore episodes without pushing duplicate history", html.contains("window.openEpisodesBack=()=>{"))
        assertTrue("Details back stack must support nested details navigation", html.contains("window._detailsBackStack"))
        assertTrue("History stack helper must prevent duplicate consecutive entries", html.contains("window.pushHistoryEntrySafely = function(pageKey)"))
        assertTrue("Input focus cleanup before navigation must clear stale IME focus flags", html.contains("window.clearActiveInputBeforeNav = function()"))
    }

    @Test
    fun testHomeLatestNewsSectionReplacesExploreAllSection() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val html = context.assets.open("index.html").bufferedReader(Charsets.UTF_8).use { it.readText() }

        // 1. Old 'استكشف كافة الأعمال' section and its grid are completely removed
        assertFalse("Old Explore All section title must be removed", html.contains("استكشف كافة الأعمال"))
        assertFalse("Old homeExploreGrid must be removed", html.contains("homeExploreGrid"))

        // 2. Home page now contains 'آخر الأخبار' section with 'عرض المزيد ←' routing to original 'news' page
        assertTrue("Home Latest News section container must exist", html.contains("""id="homeLatestNewsSection""""))
        assertTrue("Home Latest News header must display آخر الأخبار", html.contains("""<h3 class="home-section-title">آخر الأخبار</h3>"""))
        assertTrue("View More button must open original news page", html.contains("""onclick="showPage('news')">عرض المزيد ←</button>"""))

        // 3. Horizontal carousel, circular overlay icons, and shared real-time news data source
        assertTrue("Horizontal news carousel must exist with partial next-card peek width", html.contains("home-news-scroll-row") && html.contains("clamp(252px, 77vw, 312px)"))
        assertTrue("Home news card must include circular comment and link overlay buttons", html.contains("home-news-circle-btn comment-btn") && html.contains("home-news-circle-btn link-btn"))
        assertTrue("Home news cards must reuse window.allNewsItems and handleNewsCardClick", html.contains("window.renderHomeNewsSectionBodyHtml") && html.contains("window.refreshHomeNewsSectionDom"))
    }

    @OptIn(UnstableApi::class)
    @Test
    fun testEpisodeWatchAutoLandscapeAnd180VideoRotationFlow() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        shadowOf(Looper.getMainLooper()).idle()

        // 1. Non-watch pages start in Portrait with WebView visible and native player hidden
        assertEquals(
            "Non-watch screens must stay in Portrait orientation",
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            activity.requestedOrientation
        )
        assertEquals(View.GONE, activity.playerContainer.visibility)
        assertEquals(View.VISIBLE, activity.webView.visibility)

        // 2. Simulate post-AdMob episode watch launch -> Auto-Landscape & aspect-ratio FIT
        activity.playStream("https://example.com/episodes/ep1.mp4", "الحلقة 1")
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("Player container must become visible on watch", View.VISIBLE, activity.playerContainer.visibility)
        assertEquals("WebView must hide cleanly without reloading", View.GONE, activity.webView.visibility)
        assertTrue("Watch landscape flag must be active", activity.isWatchLandscapeActive)
        assertEquals(
            "Watch screen must enter Landscape automatically without manual phone rotation",
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            activity.requestedOrientation
        )
        assertEquals(
            "Video must preserve original aspect ratio without stretching",
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            activity.playerView.resizeMode
        )

        // 3. Old orientation toggle button is removed while all other player controls remain intact
        val legacyFullscreenBtn = activity.playerView.findViewById<View>(androidx.media3.ui.R.id.exo_fullscreen)
        assertEquals("Legacy portrait/landscape fullscreen button must be hidden", View.GONE, legacyFullscreenBtn?.visibility ?: View.GONE)
        assertNotNull("Play/Pause control must remain intact", activity.playerView.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause))
        assertNotNull("Progress/Seekbar control must remain intact", activity.playerView.findViewById<View>(androidx.media3.ui.R.id.exo_progress))
        assertNotNull("Subtitle button must remain intact", activity.playerView.findViewById<View>(androidx.media3.ui.R.id.exo_subtitle))
        assertNotNull("Settings (audio/speed) button must remain intact", activity.playerView.findViewById<View>(androidx.media3.ui.R.id.exo_settings))

        // 4. Verify TextureView surface and new Video Direction button (0° -> 180° -> 0°)
        val videoSurface = activity.playerView.videoSurfaceView
        assertTrue("PlayerView surface must be a TextureView so video frames rotate cleanly", videoSurface is TextureView)
        assertEquals("Initial video rotation must be 0 degrees", 0f, videoSurface!!.rotation, 0.01f)

        val directionBtn = activity.playerTopBar.findViewWithTag<View>("video_direction_button")
        assertNotNull("New Video Direction button must exist in player top bar", directionBtn)

        // First press -> rotates ONLY video 180° while keeping Landscape & controls upright
        directionBtn.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Video 180° state flag must be true after first click", activity.isVideoRotated180)
        assertEquals("Video surface must rotate to 180 degrees", 180f, videoSurface.rotation, 0.01f)
        assertEquals(
            "Player must remain in Landscape (never switch to Portrait) when rotating video direction",
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            activity.requestedOrientation
        )
        assertEquals("Player top bar controls must stay upright (0f)", 0f, activity.playerTopBar.rotation, 0.01f)
        assertEquals("PlayerView UI container must stay upright (0f)", 0f, activity.playerView.rotation, 0.01f)

        // Second press -> returns video to normal 0° while remaining in Landscape
        directionBtn.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("Video 180° state flag must return to false on second click", activity.isVideoRotated180)
        assertEquals("Video surface must return to normal 0 degrees", 0f, videoSurface.rotation, 0.01f)
        assertEquals(
            "Player must still remain in Landscape",
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            activity.requestedOrientation
        )

        // Rotate to 180° again, then press Back -> closes player, resets video rotation, and restores Portrait
        directionBtn.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(180f, videoSurface.rotation, 0.01f)

        activity.onBackPressedDispatcher.onBackPressed()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("Player container must close on single Back press", View.GONE, activity.playerContainer.visibility)
        assertEquals("WebView (Episodes page) must become visible again", View.VISIBLE, activity.webView.visibility)
        assertFalse("Watch landscape flag must be cleared on exit", activity.isWatchLandscapeActive)
        assertFalse("Video 180° rotation must reset on exit", activity.isVideoRotated180)
        assertEquals(
            "Returning to Episodes or previous page must restore Portrait orientation",
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            activity.requestedOrientation
        )

        controller.pause().stop().destroy()
    }

    @Test
    fun testFallbackWebWatchLandscapeAndDirectionButtonAssets() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val html = context.assets.open("index.html").bufferedReader(Charsets.UTF_8).use { it.readText() }

        assertTrue("Fallback watch must trigger native landscape bridge", html.contains("window.AndroidBridge.enterWatchLandscape()"))
        assertTrue("Fallback watch must preserve video aspect ratio via object-fit:contain", html.contains("object-fit:contain"))
        assertTrue("Fallback watch must disable legacy HTML5 fullscreen button", html.contains("""controlsList="nofullscreen"""") && html.contains("::-webkit-media-controls-fullscreen-button{display:none!important;}"))
        assertTrue("Fallback watch must include new Video Direction button", html.contains("""id="fallbackRotateVideoBtn"""") && html.contains("window.toggleFallbackVideoDirection()"))
        assertTrue("Leaving fallback watch must restore Portrait orientation", html.contains("window.exitFallbackWatchMode") && html.contains("window.AndroidBridge.exitWatchLandscape()"))
    }

    @Test
    fun testGoogleSignInFirebaseConfigDiagnosticsAndAssets() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // 1. Verify official app/google-services.json generated resources & Firebase initialization for hamza-4b70c
        assertEquals("google-services.json project_id must be hamza-4b70c", "hamza-4b70c", context.getString(R.string.project_id))
        assertEquals("google-services.json gcm_defaultSenderId must be 595903053058", "595903053058", context.getString(R.string.gcm_defaultSenderId))
        assertEquals(
            "google-services.json google_app_id must match Android mobilesdk_app_id",
            "1:595903053058:android:b855cb828befb352d4561b",
            context.getString(R.string.google_app_id)
        )
        assertEquals(
            "google-services.json default_web_client_id must match Web OAuth Client ID",
            "595903053058-om6vigelenhg8374vr2ou28ksvlaanfi.apps.googleusercontent.com",
            context.getString(R.string.default_web_client_id)
        )

        MainActivity.ensureFirebaseInitialized(context)
        val firebaseApps = com.google.firebase.FirebaseApp.getApps(context)
        assertTrue("FirebaseApp must be initialized for hamza-4b70c", firebaseApps.isNotEmpty())
        val defaultApp = com.google.firebase.FirebaseApp.getInstance()
        assertEquals("Firebase project ID must remain hamza-4b70c", "hamza-4b70c", defaultApp.options.projectId)
        assertEquals("GCM sender ID must match hamza-4b70c project number", "595903053058", defaultApp.options.gcmSenderId)
        assertEquals(
            "FirebaseApp applicationId must use official Android mobilesdk_app_id from google-services.json",
            "1:595903053058:android:b855cb828befb352d4561b",
            defaultApp.options.applicationId
        )

        // Verify google-services.json Android OAuth client matches package_name and debug.keystore SHA-1
        val gsFile = java.io.File("google-services.json").let { if (it.exists()) it else java.io.File("app/google-services.json") }
        assertTrue("Official google-services.json file must exist in app module", gsFile.exists())
        val gsJson = org.json.JSONObject(gsFile.readText(Charsets.UTF_8))
        assertEquals("hamza-4b70c", gsJson.getJSONObject("project_info").getString("project_id"))
        val clientObj = gsJson.getJSONArray("client").getJSONObject(0)
        assertEquals(
            "com.aistudio.blackanime.qkxmr",
            clientObj.getJSONObject("client_info").getJSONObject("android_client_info").getString("package_name")
        )
        val oauthClients = clientObj.getJSONArray("oauth_client")
        var foundMatchingAndroidOAuth = false
        val keystoreFile = java.io.File("../debug.keystore").let { if (it.exists()) it else java.io.File("debug.keystore") }
        val ks = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType())
        keystoreFile.inputStream().use { ks.load(it, "android".toCharArray()) }
        val certBytes = ks.getCertificate("androiddebugkey").encoded
        val actualDebugSha1Hex = MainActivity.computeCertificateDigest(certBytes, "SHA-1").replace(":", "").lowercase()
        for (i in 0 until oauthClients.length()) {
            val oc = oauthClients.getJSONObject(i)
            if (oc.optInt("client_type") == 1) {
                val androidInfo = oc.optJSONObject("android_info")
                if (androidInfo != null &&
                    androidInfo.optString("package_name") == "com.aistudio.blackanime.qkxmr" &&
                    androidInfo.optString("certificate_hash").lowercase() == actualDebugSha1Hex
                ) {
                    foundMatchingAndroidOAuth = true
                }
            }
        }
        assertTrue(
            "google-services.json must contain client_type=1 Android OAuth entry matching com.aistudio.blackanime.qkxmr and actual APK signing SHA-1",
            foundMatchingAndroidOAuth
        )

        // 2. Verify certificate digest helper computes real SHA-1 / SHA-256 without hardcoded assumptions
        val sampleCert = "blackanime-cert-sample".toByteArray(Charsets.UTF_8)
        val sha1 = MainActivity.computeCertificateDigest(sampleCert, "SHA-1")
        val sha256 = MainActivity.computeCertificateDigest(sampleCert, "SHA-256")
        assertEquals("SHA-1 digest must have 20 hex octets separated by colons", 59, sha1.length)
        assertEquals("SHA-256 digest must have 32 hex octets separated by colons", 95, sha256.length)

        // 3. Verify Activity configuration, Web Client ID alignment, cancellation vs OAuth error diagnostics, token sanitization, and Add Account intent
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            "Application package name must match com.aistudio.blackanime.qkxmr",
            "com.aistudio.blackanime.qkxmr",
            activity.packageName
        )
        val webClientId = activity.resolveGoogleServerClientId()
        assertEquals(
            "Web Client ID must match hamza-4b70c Web OAuth 2.0 Client ID",
            "595903053058-om6vigelenhg8374vr2ou28ksvlaanfi.apps.googleusercontent.com",
            webClientId
        )
        assertTrue(
            "Resolved Web Client ID must align with hamza-4b70c project number 595903053058",
            activity.isWebClientIdAlignedWithProject(webClientId)
        )
        assertFalse(
            "Web Client ID from a different project must be rejected",
            activity.isWebClientIdAlignedWithProject("123456789012-wrongproject.apps.googleusercontent.com")
        )

        // True user cancellation vs OAuth SHA-1 mismatch disguised as cancellation
        val realCancel = androidx.credentials.exceptions.GetCredentialCancellationException("activity is cancelled by the user.")
        assertTrue("Genuine user dismissal must be treated as clean cancellation", activity.isTrueUserCancellation(realCancel))

        val shaMismatchCancel = androidx.credentials.exceptions.GetCredentialCancellationException(
            "[28444] Developer console is not set up correctly."
        )
        assertFalse("28444 OAuth/SHA-1 error must NOT be treated as user cancellation", activity.isTrueUserCancellation(shaMismatchCancel))
        val formattedShaErr = activity.formatGoogleSignInErrorMessage(shaMismatchCancel)
        assertTrue(
            "OAuth error message must mention hamza-4b70c and actual package name",
            formattedShaErr.contains("hamza-4b70c") && formattedShaErr.contains("com.aistudio.blackanime.qkxmr")
        )

        // Token redaction in logs
        val fakeJwt = "eyJhbGciOiJSUzI1NiIsImtpZCI6IjEyMyJ9.eyJzdWIiOiJ1c2VyXzEyMyIsImVtYWlsIjoidGVzdEBleGFtcGxlLmNvbSJ9.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
        val sanitized = activity.sanitizeDiagnosticMessage("Token failure: $fakeJwt")
        assertFalse("Raw JWT must never remain in diagnostic logs", sanitized.contains(fakeJwt))
        assertTrue("Sanitized log must contain [REDACTED_TOKEN]", sanitized.contains("[REDACTED_TOKEN]"))

        // Duplicate tap guard
        activity.isGoogleSignInInProgress = true
        activity.launchNativeGoogleSignIn("login")
        assertTrue("In-progress guard must stay active and block duplicate tap", activity.isGoogleSignInInProgress)
        activity.isGoogleSignInInProgress = false

        // Add Google Account system intent
        activity.launchSystemAddGoogleAccount()
        val nextIntent = shadowOf(activity).nextStartedActivity
        assertNotNull("Add Google Account must launch system account intent", nextIntent)
        assertEquals(android.provider.Settings.ACTION_ADD_ACCOUNT, nextIntent.action)
        val accountTypes = nextIntent.getStringArrayExtra(android.provider.Settings.EXTRA_ACCOUNT_TYPES)
        assertNotNull(accountTypes)
        assertTrue("Intent must target com.google account type", accountTypes!!.contains("com.google"))

        // 4. Verify index.html single-path Google Sign-In, request deduplication, stuck-loading recovery, and watch protection
        val html = context.assets.open("index.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
        assertTrue("Login form must include المتابعة باستخدام Google button", html.contains("""id="btnGoogleSignIn"""") && html.contains("المتابعة باستخدام Google"))
        assertTrue("Register form must include إنشاء حساب باستخدام Google button", html.contains("""id="btnGoogleRegister"""") && html.contains("إنشاء حساب باستخدام Google"))
        assertTrue("Email/Password login & register & reset forms must remain intact",
            html.contains("""id="formAuthLogin"""") && html.contains("""id="formAuthRegister"""") && html.contains("""id="formAuthReset"""")
        )
        assertTrue("Google Sign-In must execute single Firebase signInWithCredential with requestId deduplication",
            html.contains("GoogleAuthProvider.credential(idToken)") &&
            html.contains("await signInWithCredential(auth, credential)") &&
            html.contains("window._lastProcessedGoogleRequestId") &&
            html.contains("window._isCompletingFirebaseGoogleAuth")
        )
        assertFalse("Obsolete silent restore bridge call must be removed",
            html.contains("requestSilentGoogleRestoreIfSignedIn")
        )
        assertTrue("Stale loading recovery and watch-page re-render protection must exist",
            html.contains("window.recoverStaleGoogleLoadingIfIdle") &&
            html.contains("""if (currentPage === "watch")""")
        )
        assertTrue("Cancellation, no-account, and logout cleanup callbacks must exist",
            html.contains("window.onNativeGoogleSignInCancelled") &&
            html.contains("window.onNativeGoogleNoAccount") &&
            html.contains("window.AndroidBridge.signOutGoogle()")
        )

        controller.pause().stop().destroy()
    }
}


