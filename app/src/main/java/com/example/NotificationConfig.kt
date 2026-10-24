package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * =========================================================================
 * 🔔 إعدادات وتخصيص نظام الإشعارات (BLACK ANIME NOTIFICATION CONFIG)
 * =========================================================================
 * يمكنك من هذا الملف التحكم الكامل في الإشعارات وتحديد:
 * 1. عنوان الإشعار (title)
 * 2. نص الإشعار / الرسالة (message)
 * 3. الصفحة المراد فتحها (targetPage): "home", "details", "anime", "movies", إلخ
 * 4. معرف العمل / الأنمي في Firebase (contentId) أو اسم العمل (contentTitle)
 * 5. رقم الحلقة المراد فتحها أو تشغيلها مباشرة (episodeNumber)
 * 6. رابط صورة البوستر الاختياري (imageUrl)
 * 7. نوع الإجراء عند الضغط (action): "play" لتشغيل الحلقة فوراً، أو "details" لفتح صفحة العمل
 * 8. رابط خارجي اختياري (externalUrl)
 * =========================================================================
 */
object NotificationConfig {

    private const val TAG = "NotificationConfig"

    // =====================================================================
    // 📌 مثال الإشعار المطلوب (قابل للتعديل المباشر من هنا):
    // =====================================================================
    // 1. عنوان الإشعار:
    var title: String = "حلقة جديدة"

    // 2. رسالة الإشعار:
    var message: String = "تم إصدار الحلقة الجديدة، اضغط للمشاهدة"

    // 3. الصفحة المستهدفة: "details" (تفاصيل العمل والحلقات), "home", "anime", "movies", "favorites"
    var targetPage: String = "details"

    // 4. معرف الأنمي أو الفيلم في Firebase Firestore (Document ID):
    // اتركه فارغاً إذا كنت تريد البحث بالاسم، أو ضع المعرف مثل: "k8Z12abc..."
    var contentId: String = ""

    // 5. أو اسم الأنمي/الفيلم للبحث عنه تلقائياً:
    var contentTitle: String = ""

    // 6. رقم الحلقة المحددة (مثلاً: 25 أو "25" أو "الحلقة 25"):
    var episodeNumber: String = "25"

    // 7. نوع الإجراء:
    // "play": يقوم بفتح التطبيق وتشغيل الحلقة مباشرة عبر مشغل الفيديو (ExoPlayer)
    // "details": يقوم بفتح صفحة تفاصيل العمل وقائمة الحلقات
    var action: String = "play"

    // 8. رابط صورة البوستر أو الإشعار (اختياري - يدعم روابط JPG/PNG):
    var imageUrl: String = ""

    // 9. رابط خارجي اختياري (إذا أردت توجيه المستخدم لصفحة أو رابط خارجي):
    var externalUrl: String = ""

    /**
     * إرسال الإشعار المضبوط بالقيم الافتراضية أعلاه مباشرة على هذا الجهاز
     */
    fun sendConfiguredNotification(context: Context) {
        sendCustomNotification(
            context = context,
            title = title,
            message = message,
            page = targetPage,
            contentId = contentId,
            episode = episodeNumber,
            url = externalUrl,
            action = action,
            imageUrl = imageUrl,
            contentTitle = contentTitle
        )
    }

    /**
     * إرسال إشعار مخصص بأي قيم تريدها برمجياً
     */
    fun sendCustomNotification(
        context: Context,
        title: String,
        message: String,
        page: String = "details",
        contentId: String = "",
        episode: String = "",
        url: String = "",
        action: String = "play",
        imageUrl: String = "",
        contentTitle: String = "",
        notifId: String = ""
    ) {
        if (notifId.isNotBlank()) {
            MyFirebaseMessagingService.markNotificationDelivered(context.applicationContext, notifId)
        }
        val validImageUrl = if (imageUrl.startsWith("http://", ignoreCase = true) ||
            imageUrl.startsWith("https://", ignoreCase = true)
        ) imageUrl else ""

        if (validImageUrl.isNotBlank()) {
            CoroutineScope(Dispatchers.IO).launch {
                val bitmap = downloadBitmap(validImageUrl)
                MyFirebaseMessagingService.showNotification(
                    context = context.applicationContext,
                    title = title,
                    body = message,
                    page = page,
                    firebaseId = contentId,
                    episode = episode,
                    url = url,
                    action = action,
                    bitmap = bitmap,
                    contentTitle = contentTitle,
                    notifId = notifId
                )
            }
        } else {
            MyFirebaseMessagingService.showNotification(
                context = context.applicationContext,
                title = title,
                body = message,
                page = page,
                firebaseId = contentId,
                episode = episode,
                url = url,
                action = action,
                bitmap = null,
                contentTitle = contentTitle,
                notifId = notifId
            )
        }
    }

    /**
     * بث إشعار حقيقي إلى جميع المستخدمين المشتركين عبر Firebase (`app_notifications` + Topic `all_users`)
     */
    fun broadcastNotificationToAllUsers(
        context: Context,
        title: String,
        message: String,
        page: String = "details",
        contentId: String = "",
        episode: String = "",
        url: String = "",
        action: String = "play",
        imageUrl: String = "",
        contentTitle: String = "",
        notifType: String = "manual_broadcast",
        onComplete: ((Boolean, String, Int) -> Unit)? = null
    ) {
        val db = FirebaseFirestore.getInstance()
        val validRemoteImage = if (imageUrl.startsWith("http://", ignoreCase = true) ||
            imageUrl.startsWith("https://", ignoreCase = true)
        ) imageUrl.trim() else ""

        db.collection("fcm_devices").get()
            .addOnCompleteListener { devicesTask ->
                val registeredCount = if (devicesTask.isSuccessful) {
                    devicesTask.result?.size() ?: 1
                } else {
                    1
                }
                val docRef = db.collection("app_notifications").document()
                val notifId = docRef.id
                val senderDeviceId = MyFirebaseMessagingService.getDeviceId(context)

                // Mark as delivered on sender device first and show local notification so no duplicate occurs
                MyFirebaseMessagingService.markNotificationDelivered(context.applicationContext, notifId)

                val payload = hashMapOf<String, Any>(
                    "notifId" to notifId,
                    "title" to title.ifBlank { "تمت إضافة حلقة جديدة!" },
                    "body" to message,
                    "message" to message,
                    "page" to page.ifBlank { "details" },
                    "firebaseId" to contentId,
                    "contentId" to contentId,
                    "contentTitle" to contentTitle,
                    "episode" to episode,
                    "episodeIndex" to episode,
                    "url" to url,
                    "action" to action.ifBlank { "play" },
                    "imageUrl" to validRemoteImage,
                    "poster" to validRemoteImage,
                    "topic" to "all_users",
                    "type" to notifType,
                    "senderDeviceId" to senderDeviceId,
                    "targetDevicesCount" to registeredCount.coerceAtLeast(1),
                    "deliveredDevices" to listOf(senderDeviceId),
                    "deliveredCount" to 1,
                    "createdAtMs" to System.currentTimeMillis(),
                    "createdAt" to FieldValue.serverTimestamp()
                )

                docRef.set(payload)
                    .addOnSuccessListener {
                        Log.d(TAG, "Broadcast notification published to all_users ($notifId), targets: $registeredCount")
                        sendCustomNotification(
                            context = context,
                            title = title,
                            message = message,
                            page = page,
                            contentId = contentId,
                            episode = episode,
                            url = url,
                            action = action,
                            imageUrl = validRemoteImage,
                            contentTitle = contentTitle,
                            notifId = notifId
                        )
                        onComplete?.invoke(true, notifId, registeredCount.coerceAtLeast(1))
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Failed to publish broadcast notification: ${e.message}", e)
                        onComplete?.invoke(false, e.message ?: "خطأ غير معروف في الإرسال", 0)
                    }
            }
    }

    private fun downloadBitmap(imageUrl: String): Bitmap? {
        return try {
            val url = URL(imageUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                doInput = true
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = true
                connect()
            }
            val input: InputStream = connection.inputStream
            BitmapFactory.decodeStream(input)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to download notification image: ${e.message}")
            null
        }
    }
}
