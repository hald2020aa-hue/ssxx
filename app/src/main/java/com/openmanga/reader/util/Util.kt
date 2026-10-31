package com.openmanga.reader.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.openmanga.engine.Diagnostic
import com.openmanga.engine.Severity
import java.io.File
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date
import java.util.Locale

fun isOnline(ctx: Context): Boolean {
    val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return true
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

fun formatTime(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

/** Turns the engine's stable diagnostic codes into plain-language explanations. */
object Diag {
    private val arabic get() = Locale.getDefault().language == "ar"

    fun explain(d: Diagnostic): String {
        if (!arabic) return d.message
        return when {
            d.code == "NETWORK" -> "تعذّر الاتصال بالموقع. تحقق من الإنترنت ومن صحة العنوان. (${d.message})"
            d.code == "ANTI_BOT" -> "الموقع يعرض تحققاً من المتصفح (حماية ضد الروبوتات). افتح الموقع في المتصفح المدمج وأكمل التحقق ثم أعد المحاولة. التطبيق لا يتجاوز هذه الحماية."
            d.code == "ACCESS_DENIED" -> "رفض الموقع الوصول (تسجيل دخول، أو تقييد منطقة، أو رأس Referer مطلوب). أضف الرؤوس أو سجّل الدخول من المتصفح المدمج."
            d.code.startsWith("HTTP_") -> "أجاب الموقع بالخطأ ${d.code.removePrefix("HTTP_")}. ${d.message}"
            d.code == "NO_ITEMS" -> "لم يطابق المحدد أي عمل في الصفحة. إما أن الصفحة تحتاج JavaScript (فعّل وضع WebView) أو أن تصميم الموقع تغيّر. ${d.message}"
            d.code == "FIELDS_EMPTY" -> "وُجدت عناصر لكن محددات العنوان/الرابط لم تُنتج شيئاً."
            d.code == "PARSE_JSON" -> "الاستجابة ليست JSON صالحاً. ${d.message}"
            d.code == "NOT_CONFIGURED" -> "هذا المصدر لا يملك إعداداً لهذه القائمة."
            d.code == "NO_CHAPTERS" -> "لم يُعثر على أي فصل. افحص محددات الفصول أو طلب AJAX، أو فعّل وضع WebView."
            d.code == "NO_PAGES" -> "لم يُعثر على صور صفحات في هذا الفصل. قد تُحمَّل بـ JavaScript (فعّل WebView) أو حدّد محدد الصور يدوياً."
            d.code == "NOT_AN_IMAGE" -> "الملف الذي نُزّل ليس صورة صالحة (غالباً صفحة خطأ HTML)."
            d.code == "PARTIAL" -> "قائمة الفصول قد تكون ناقصة لأن إحدى الصفحات فشلت."
            d.code == "TRUNCATED" -> "توقف الجلب عند حد الأمان؛ قد توجد فصول أخرى."
            d.code == "NO_LAYOUT" -> "لم أتعرّف على قائمة أعمال في الصفحة الرئيسية. جرّب وضع WebView أو أدخل المحددات يدوياً."
            d.code == "NOT_VERIFIED" -> "خُمّن تصميم الموقع لكنه لم ينجح في الاختبار الكامل. حُفظ كمسودة لتعديلها يدوياً."
            d.code == "VERIFIED" -> "تم التحقق: قائمة ← فصول ← صور صفحات، كلها تعمل."
            d.code == "BAD_URL" -> "عنوان الموقع غير صالح."
            d.code == "UNEXPECTED" -> "حدث خطأ غير متوقع في هذا المصدر ولم يؤثر على بقية المصادر. ${d.message}"
            else -> d.message
        }
    }

    /** The most useful single sentence for a failed operation. */
    fun summary(diags: List<Diagnostic>): String =
        diags.firstOrNull { it.severity == Severity.ERROR }?.let { explain(it) } ?: diags.firstOrNull()?.let { explain(it) } ?: ""

    fun all(diags: List<Diagnostic>): String =
        diags.joinToString("\n\n") { "[${it.stage}/${it.code}] ${explain(it)}${it.url?.let { u -> "\n$u" } ?: ""}" }

    fun needsBrowser(diags: List<Diagnostic>) = diags.any { it.code == "ANTI_BOT" || it.code == "ACCESS_DENIED" }
}

object Files {
    private fun safe(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(60).ifEmpty { "x" }

    private fun hash8(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(8)

    /** App-specific storage: needs no storage permission on any Android version. */
    fun chapterDir(ctx: Context, sourceId: String, mangaTitle: String, chapterName: String, chapterUrl: String): File {
        val root = ctx.getExternalFilesDir("chapters") ?: File(ctx.filesDir, "chapters")
        return File(root, "${safe(sourceId)}/${safe(mangaTitle)}/${safe(chapterName)}_${hash8(chapterUrl)}")
    }

    fun isComplete(dir: File) = File(dir, ".complete").exists()

    fun localPages(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && !f.name.startsWith(".") }?.sortedBy { it.name } ?: emptyList()

    fun extensionFor(mime: String?) = when (mime) {
        "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/webp" -> "webp"; "image/gif" -> "gif"
        "image/avif" -> "avif"; "image/heic" -> "heic"; "image/bmp" -> "bmp"; else -> "img"
    }
}
