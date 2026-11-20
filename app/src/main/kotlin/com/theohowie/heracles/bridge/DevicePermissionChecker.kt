package com.theohowie.heracles.bridge

import android.content.Context
import android.content.pm.PackageManager

object DevicePermissionChecker {

    const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"

    fun hasWriteSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun guidance(context: Context): String {
        if (hasWriteSecureSettings(context)) return ""
        val pkg = context.packageName
        return """
            |يحتاج Heracles إلى إذن لمرة واحدة (لتشخيص قدرات النظام والإصلاح التلقائي).
            |اختر إحدى الطرق الثلاث:
            |
            |① تصحيح الأخطاء اللاسلكي (Android 11+، بدون كمبيوتر)
            |   خيارات المطور ← تصحيح الأخطاء اللاسلكي ← بعد الإقران نفّذ:
            |   adb shell pm grant $pkg $WRITE_SECURE_SETTINGS
            |
            |② عبر الكمبيوتر (adb)
            |   وصّل الهاتف بالكمبيوتر ونفّذ نفس الأمر أعلاه.
            |
            |③ Shizuku / Stellar
            |   بعد تشغيله اضغط طلب الإذن من «أذونات الجهاز والبقاء في الخلفية» داخل التطبيق.
        """.trimMargin()
    }
}
