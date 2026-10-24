package com.example

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class MyFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "FCM_Service"
        const val CHANNEL_ID = "black_anime_notifications"
        const val CHANNEL_NAME = "إشعارات BLACK ANIME"
        const val PREFS_NAME = "black_anime_fcm_prefs"
        const val KEY_FCM_TOKEN = "fcm_token"
        const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
        const val KEY_DELIVERED_IDS = "delivered_notif_ids"
        const val KEY_INSTALL_TIME = "first_install_timestamp"

        private var firestoreBroadcastListener: ListenerRegistration? = null

        fun getDeviceId(context: Context): String {
            return try {
                val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                if (!androidId.isNullOrBlank()) androidId else "dev_${Build.MODEL}_${Build.ID}".replace(" ", "_")
            } catch (e: Exception) {
                "dev_${Build.MODEL}".replace(" ", "_")
            }
        }

        fun getOrInitInstallTimestamp(context: Context): Long {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            var ts = prefs.getLong(KEY_INSTALL_TIME, 0L)
            if (ts == 0L) {
                ts = System.currentTimeMillis() - 60_000L // allow notifications from 1 minute ago on first launch
                prefs.edit().putLong(KEY_INSTALL_TIME, ts).apply()
            }
            return ts
        }

        @Synchronized
        fun hasNotificationBeenDelivered(context: Context, notifId: String): Boolean {
            if (notifId.isBlank()) return false
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val set = prefs.getStringSet(KEY_DELIVERED_IDS, emptySet()) ?: emptySet()
            return set.contains(notifId)
        }

        @Synchronized
        fun markNotificationDelivered(context: Context, notifId: String) {
            if (notifId.isBlank()) return
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val existing = prefs.getStringSet(KEY_DELIVERED_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
            existing.add(notifId)
            // Keep size bounded to latest 300 IDs
            val trimmed = if (existing.size > 300) existing.toList().takeLast(300).toSet() else existing
            prefs.edit().putStringSet(KEY_DELIVERED_IDS, trimmed).apply()
        }

        fun isAppNotificationsEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_NOTIFICATIONS_ENABLED, true)
        }

        fun isAdminDevice(context: Context): Boolean {
            val prefs = context.getSharedPreferences("app_auth_prefs", Context.MODE_PRIVATE)
            return prefs.getString("user_role", "") == "ADMIN"
        }

        fun setAppNotificationsEnabled(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_NOTIFICATIONS_ENABLED, enabled).apply()
            try {
                if (enabled) {
                    FirebaseMessaging.getInstance().subscribeToTopic("all_users")
                    FirebaseMessaging.getInstance().subscribeToTopic("anime_updates")
                    if (isAdminDevice(context)) {
                        FirebaseMessaging.getInstance().subscribeToTopic("admin_alerts")
                    }
                } else {
                    FirebaseMessaging.getInstance().unsubscribeFromTopic("all_users")
                    FirebaseMessaging.getInstance().unsubscribeFromTopic("anime_updates")
                    FirebaseMessaging.getInstance().unsubscribeFromTopic("admin_alerts")
                    NotificationManagerCompat.from(context).cancelAll()
                }
                syncDeviceRegistrationInFirestore(context, getSavedToken(context))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update FCM topic subscription: ${e.message}")
            }
        }

        fun getSavedToken(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_FCM_TOKEN, "") ?: ""
        }

        fun saveToken(context: Context, token: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_FCM_TOKEN, token).apply()
            syncDeviceRegistrationInFirestore(context, token)
        }

        /**
         * Registers or updates this device and its FCM token in Firebase Firestore (`fcm_devices/{deviceId}`)
         * so the Admin dashboard can see the exact count of registered devices and target them reliably.
         */
        fun syncDeviceRegistrationInFirestore(context: Context, token: String) {
            try {
                val deviceId = getDeviceId(context)
                val appEnabled = isAppNotificationsEnabled(context)
                val sysEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
                val isAdmin = isAdminDevice(context)
                val topicsList = if (appEnabled) {
                    if (isAdmin) listOf("all_users", "anime_updates", "admin_alerts")
                    else listOf("all_users", "anime_updates")
                } else emptyList<String>()

                val data = hashMapOf<String, Any>(
                    "deviceId" to deviceId,
                    "token" to token,
                    "model" to "${Build.MANUFACTURER} ${Build.MODEL}",
                    "sdkInt" to Build.VERSION.SDK_INT,
                    "isAdmin" to isAdmin,
                    "notificationsEnabled" to (appEnabled && sysEnabled),
                    "appSettingEnabled" to appEnabled,
                    "systemPermissionGranted" to sysEnabled,
                    "subscribedTopics" to topicsList,
                    "updatedAt" to FieldValue.serverTimestamp(),
                    "lastSeenMs" to System.currentTimeMillis()
                )
                FirebaseFirestore.getInstance()
                    .collection("fcm_devices")
                    .document(deviceId)
                    .set(data, SetOptions.merge())
                    .addOnSuccessListener {
                        Log.d(TAG, "Device registered/updated in Firestore fcm_devices/$deviceId")
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "Failed to register device in Firestore: ${e.message}")
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing device registration: ${e.message}", e)
            }
        }

        /**
         * Starts a real-time Firestore listener on `app_notifications` so that every connected device
         * receives broadcast notifications and automatic new-episode notifications immediately
         * with full poster image, title, episode deep-link, and delivery receipt tracking.
         */
        fun startRealtimeBroadcastListener(context: Context) {
            val appContext = context.applicationContext
            getOrInitInstallTimestamp(appContext)
            if (firestoreBroadcastListener != null) return

            try {
                firestoreBroadcastListener = FirebaseFirestore.getInstance()
                    .collection("app_notifications")
                    .orderBy("createdAtMs", Query.Direction.DESCENDING)
                    .limit(15)
                    .addSnapshotListener { snapshots, error ->
                        if (error != null) {
                            Log.w(TAG, "Firestore app_notifications listener error: ${error.message}")
                            return@addSnapshotListener
                        }
                        if (snapshots == null) return@addSnapshotListener

                        val installTime = getOrInitInstallTimestamp(appContext)

                        for (change in snapshots.documentChanges) {
                            if (change.type == DocumentChange.Type.ADDED || change.type == DocumentChange.Type.MODIFIED) {
                                val doc = change.document
                                val notifId = doc.id
                                val createdAtMs = doc.getLong("createdAtMs") ?: 0L

                                // Only display notifications created after this app installation (or within last 10 mins)
                                if (createdAtMs < installTime - 600_000L) {
                                    continue
                                }

                                // Prevent duplicate notifications using unique notifId
                                if (hasNotificationBeenDelivered(appContext, notifId)) {
                                    continue
                                }

                                val title = doc.getString("title") ?: "تمت إضافة حلقة جديدة!"
                                val body = doc.getString("body") ?: doc.getString("message") ?: ""
                                val page = doc.getString("page") ?: "details"
                                val firebaseId = doc.getString("firebaseId") ?: doc.getString("contentId") ?: ""
                                val episode = doc.getString("episode") ?: doc.getString("episodeIndex") ?: ""
                                val url = doc.getString("url") ?: ""
                                val action = doc.getString("action") ?: "play"
                                val contentTitle = doc.getString("contentTitle") ?: ""
                                val imageUrl = doc.getString("imageUrl") ?: doc.getString("poster") ?: ""
                                val targetAudience = doc.getString("targetAudience") ?: "all_users"
                                val targetDeviceId = doc.getString("targetDeviceId") ?: ""
                                val currentDeviceId = getDeviceId(appContext)

                                // Filter notifications meant only for Admin devices
                                if (targetAudience == "admin_only" && !isAdminDevice(appContext)) {
                                    continue
                                }

                                // Filter notifications meant for a specific reporter device
                                if (targetAudience == "specific_device" && targetDeviceId.isNotBlank() && targetDeviceId != currentDeviceId) {
                                    continue
                                }

                                markNotificationDelivered(appContext, notifId)

                                if (!isAppNotificationsEnabled(appContext)) {
                                    Log.d(TAG, "Skipping broadcast $notifId because user disabled app notifications.")
                                    continue
                                }

                                CoroutineScope(Dispatchers.IO).launch {
                                    val validRemoteImage = if (imageUrl.startsWith("http://", ignoreCase = true) ||
                                        imageUrl.startsWith("https://", ignoreCase = true)
                                    ) imageUrl else ""

                                    val bitmap = if (validRemoteImage.isNotBlank()) {
                                        downloadBitmapStatic(validRemoteImage)
                                    } else {
                                        null
                                    }

                                    showNotification(
                                        context = appContext,
                                        title = title,
                                        body = body,
                                        page = page,
                                        firebaseId = firebaseId,
                                        episode = episode,
                                        url = url,
                                        action = action,
                                        bitmap = bitmap,
                                        contentTitle = contentTitle,
                                        notifId = notifId
                                    )

                                    // Acknowledge delivery in Firestore so Admin sees real delivered device count
                                    try {
                                        val deviceId = getDeviceId(appContext)
                                        FirebaseFirestore.getInstance()
                                            .collection("app_notifications")
                                            .document(notifId)
                                            .update(
                                                mapOf(
                                                    "deliveredDevices" to FieldValue.arrayUnion(deviceId),
                                                    "deliveredCount" to FieldValue.increment(1)
                                                )
                                            )
                                    } catch (ackErr: Exception) {
                                        Log.w(TAG, "Could not update delivery receipt for $notifId: ${ackErr.message}")
                                    }
                                }
                            }
                        }
                    }
                Log.d(TAG, "Realtime broadcast listener started on collection: app_notifications")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start realtime broadcast listener: ${e.message}", e)
            }
        }

        fun downloadBitmapStatic(imageUrl: String): Bitmap? {
            return try {
                val url = URL(imageUrl)
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    doInput = true
                    connectTimeout = 8000
                    readTimeout = 8000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                    connect()
                }
                connection.inputStream.use { input ->
                    BitmapFactory.decodeStream(input)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not download notification image ($imageUrl): ${e.message}")
                null
            }
        }

        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                if (notificationManager != null) {
                    val existingChannel = notificationManager.getNotificationChannel(CHANNEL_ID)
                    if (existingChannel == null) {
                        val channel = NotificationChannel(
                            CHANNEL_ID,
                            CHANNEL_NAME,
                            NotificationManager.IMPORTANCE_HIGH
                        ).apply {
                            description = "إشعارات الحلقات والأنميات والتحديثات لتطبيق BLACK ANIME"
                            enableLights(true)
                            lightColor = ContextCompat.getColor(context, R.color.notification_accent)
                            enableVibration(true)
                            setShowBadge(true)
                        }
                        notificationManager.createNotificationChannel(channel)
                        Log.d(TAG, "Notification channel created: $CHANNEL_ID")
                    }
                }
            }
        }

        fun showNotification(
            context: Context,
            title: String,
            body: String,
            page: String,
            firebaseId: String,
            episode: String,
            url: String,
            action: String,
            bitmap: Bitmap?,
            contentTitle: String = "",
            notifId: String = ""
        ) {
            if (!isAppNotificationsEnabled(context)) {
                Log.d(TAG, "Notifications are disabled in app settings. Skipping notification display.")
                return
            }
            createNotificationChannel(context)

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("fcm_page", page)
                putExtra("page", page)
                putExtra("target_page", page)
                putExtra("screen", page)
                putExtra("fcm_firebaseId", firebaseId)
                putExtra("firebaseId", firebaseId)
                putExtra("id", firebaseId)
                putExtra("contentId", firebaseId)
                putExtra("animeId", firebaseId)
                putExtra("movieId", firebaseId)
                putExtra("fcm_episode", episode)
                putExtra("episode", episode)
                putExtra("episodeIndex", episode)
                putExtra("episodeNumber", episode)
                putExtra("ep", episode)
                putExtra("fcm_url", url)
                putExtra("url", url)
                putExtra("link", url)
                putExtra("fcm_action", action)
                putExtra("action", action)
                putExtra("fcm_title", title)
                putExtra("title", title)
                putExtra("contentTitle", contentTitle)
                putExtra("fcm_contentTitle", contentTitle)
                putExtra("animeTitle", contentTitle)
                putExtra("notifId", notifId)
                putExtra("from_notification", true)
            }

            val notificationId = if (notifId.isNotBlank()) {
                notifId.hashCode() and 0x7FFFFFFF
            } else {
                (System.currentTimeMillis() % 100000).toInt()
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                notificationId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val finalTitle = title.ifBlank { NotificationConfig.title.ifBlank { "BLACK ANIME" } }
            val finalBody = body.ifBlank { NotificationConfig.message }

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(ContextCompat.getColor(context, R.color.notification_accent))
                .setContentTitle(finalTitle)
                .setContentText(finalBody)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setContentIntent(pendingIntent)

            if (bitmap != null) {
                builder.setLargeIcon(bitmap)
                builder.setStyle(
                    NotificationCompat.BigPictureStyle()
                        .bigPicture(bitmap)
                        .bigLargeIcon(null as Bitmap?)
                        .setBigContentTitle(finalTitle)
                        .setSummaryText(finalBody)
                )
            } else if (finalBody.isNotBlank()) {
                builder.setStyle(
                    NotificationCompat.BigTextStyle()
                        .setBigContentTitle(finalTitle)
                        .bigText(finalBody)
                )
            }

            try {
                val notificationManager = NotificationManagerCompat.from(context)
                notificationManager.notify(notificationId, builder.build())
                Log.d(TAG, "Notification displayed: $finalTitle (target: $firebaseId ep: $episode, id: $notifId)")
            } catch (e: SecurityException) {
                Log.e(TAG, "Missing notification permission: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to display notification: ${e.message}", e)
            }
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM Token received: $token")
        saveToken(applicationContext, token)

        // Automatically subscribe device to global topics for broadcasting if enabled
        if (isAppNotificationsEnabled(applicationContext)) {
            try {
                FirebaseMessaging.getInstance().subscribeToTopic("all_users")
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            Log.d(TAG, "Subscribed successfully to topic: all_users")
                        } else {
                            Log.e(TAG, "Failed subscribing to topic all_users", task.exception)
                        }
                    }
                FirebaseMessaging.getInstance().subscribeToTopic("anime_updates")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to subscribe to topics", e)
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "FCM Message received from: ${remoteMessage.from}, data=${remoteMessage.data}")

        val notifId = remoteMessage.data["notifId"]
            ?: remoteMessage.data["fcm_notif_id"]
            ?: remoteMessage.messageId
            ?: ""

        if (notifId.isNotBlank() && hasNotificationBeenDelivered(applicationContext, notifId)) {
            Log.d(TAG, "Duplicate FCM notification ($notifId) ignored (already delivered).")
            return
        }
        if (notifId.isNotBlank()) {
            markNotificationDelivered(applicationContext, notifId)
        }

        // 1. Extract Title & Body
        val title = remoteMessage.notification?.title
            ?: remoteMessage.data["title"]
            ?: remoteMessage.data["fcm_title"]
            ?: remoteMessage.data["heading"]
            ?: NotificationConfig.title

        val body = remoteMessage.notification?.body
            ?: remoteMessage.data["body"]
            ?: remoteMessage.data["message"]
            ?: remoteMessage.data["fcm_body"]
            ?: NotificationConfig.message

        // 2. Extract Navigation & Action Data (supports anime, episode, page, URL, action)
        val page = remoteMessage.data["page"]
            ?: remoteMessage.data["fcm_page"]
            ?: remoteMessage.data["target_page"]
            ?: remoteMessage.data["screen"]
            ?: "details"

        val firebaseId = remoteMessage.data["firebaseId"]
            ?: remoteMessage.data["id"]
            ?: remoteMessage.data["contentId"]
            ?: remoteMessage.data["animeId"]
            ?: remoteMessage.data["movieId"]
            ?: remoteMessage.data["fcm_firebaseId"]
            ?: ""

        val contentTitle = remoteMessage.data["contentTitle"]
            ?: remoteMessage.data["fcm_contentTitle"]
            ?: remoteMessage.data["animeTitle"]
            ?: remoteMessage.data["movieTitle"]
            ?: remoteMessage.data["name"]
            ?: ""

        val episode = remoteMessage.data["episodeIndex"]
            ?: remoteMessage.data["episode"]
            ?: remoteMessage.data["ep"]
            ?: remoteMessage.data["episodeNumber"]
            ?: remoteMessage.data["fcm_episode"]
            ?: ""

        val url = remoteMessage.data["url"]
            ?: remoteMessage.data["link"]
            ?: remoteMessage.data["fcm_url"]
            ?: ""

        val action = remoteMessage.data["action"]
            ?: remoteMessage.data["fcm_action"]
            ?: (if (episode.isNotBlank()) "play" else "details")

        // 3. Extract Optional Image URL
        val imageUrl = remoteMessage.notification?.imageUrl?.toString()
            ?: remoteMessage.data["image"]
            ?: remoteMessage.data["image_url"]
            ?: remoteMessage.data["poster"]
            ?: remoteMessage.data["fcm_image"]

        // 4. Download image if provided and valid HTTP(S) URL
        val bitmap = if (!imageUrl.isNullOrBlank() &&
            (imageUrl.startsWith("http://", ignoreCase = true) || imageUrl.startsWith("https://", ignoreCase = true))
        ) {
            downloadBitmapStatic(imageUrl)
        } else {
            null
        }

        // 5. Display the notification
        showNotification(
            applicationContext,
            title,
            body,
            page,
            firebaseId,
            episode,
            url,
            action,
            bitmap,
            contentTitle,
            notifId
        )
    }
}

