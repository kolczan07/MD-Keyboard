package pl.mdinstal.ergokeyboard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(val version: String, val url: String, val notes: String, val size: Long)

/** Aktualizacje z GitHub Releases (repo publiczne). Wszystkie metody sieciowe blokujące: wywołuj poza wątkiem UI. */
object Updater {
    private const val REPO = "kolczan07/MD-Keyboard"

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "MD-Keypad")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    // Usuwa z notatek wydania linię "Full Changelog: ..." dopisywaną automatycznie przez GitHub
    private fun cleanNotes(body: String): String =
        body.lines().filterNot { it.contains("Full Changelog", ignoreCase = true) }.joinToString("\n").trim()

    /** Zwykłe wydanie: najnowsze (GitHub pomija w "latest" pre-release), plik APK bez "debug" w nazwie. */
    fun latest(): UpdateInfo? {
        val json = JSONObject(get("https://api.github.com/repos/$REPO/releases/latest"))
        val tag = json.optString("tag_name").removePrefix("v").removePrefix("V")
        val assets = json.optJSONArray("assets") ?: return null
        var fallback: UpdateInfo? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            val info = UpdateInfo(tag, a.getString("browser_download_url"), cleanNotes(json.optString("body")), a.optLong("size"))
            if (!name.contains("debug", ignoreCase = true)) return info
            if (fallback == null) fallback = info
        }
        return fallback
    }

    /** Z listy plików wydania wybiera archiwum ZIP (z APK w środku) albo bezpośrednio APK z "debug" w nazwie. */
    private fun debugAsset(json: JSONObject): UpdateInfo? {
        val tag = json.optString("tag_name").removePrefix("v").removePrefix("V")
        val assets = json.optJSONArray("assets") ?: return null
        var apk: UpdateInfo? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name")
            val info = UpdateInfo(tag, a.getString("browser_download_url"), cleanNotes(json.optString("body")), a.optLong("size"))
            if (name.endsWith(".zip", ignoreCase = true)) return info
            if (name.endsWith(".apk", ignoreCase = true) && name.contains("debug", ignoreCase = true) && apk == null) apk = info
        }
        return apk
    }

    /** Debug: stałe wydanie z tagiem "debug" (workflow nadpisuje je przy każdym buildzie). */
    fun latestDebug(): UpdateInfo? {
        try {
            debugAsset(JSONObject(get("https://api.github.com/repos/$REPO/releases/tags/debug")))?.let { return it }
        } catch (e: Exception) {
        }
        val list = JSONArray(get("https://api.github.com/repos/$REPO/releases?per_page=10"))
        for (i in 0 until list.length()) {
            val a = list.getJSONObject(i)
            val assets = a.optJSONArray("assets") ?: continue
            val hasZip = (0 until assets.length()).any { assets.getJSONObject(it).optString("name").endsWith(".zip", true) }
            if (hasZip) debugAsset(a)?.let { return it }
        }
        return null
    }

    /** Porównuje wersje semantycznie: "1.3" vs "1.2" → true. */
    fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.split('.', '-').map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
        val r = parts(remote)
        val l = parts(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    fun download(ctx: Context, info: UpdateInfo, onProgress: (Int) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.deleteRecursively() } }
        val f = File(dir, if (info.url.substringBefore('?').endsWith(".zip", ignoreCase = true)) "update.zip" else "update.apk")
        val c = URL(info.url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            val total = if (c.contentLengthLong > 0) c.contentLengthLong else info.size
            c.inputStream.use { input ->
                FileOutputStream(f).use { out ->
                    val buf = ByteArray(32 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done * 100 / total).toInt()
                            if (p != last) {
                                last = p
                                onProgress(p)
                            }
                        }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        return f
    }

    /** Rozpakowuje ZIP do cache/updates/temp i zwraca znaleziony APK (z ochroną przed zip-slip). */
    fun extractApk(zip: File): File {
        val dest = File(zip.parentFile, "temp").apply { deleteRecursively(); mkdirs() }
        val root = dest.canonicalPath + File.separator
        var apk: File? = null
        java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                val out = File(dest, e.name)
                if (!out.canonicalPath.startsWith(root)) throw IOException("Nieprawidłowa ścieżka w archiwum")
                if (e.isDirectory) {
                    out.mkdirs()
                    continue
                }
                out.parentFile?.mkdirs()
                FileOutputStream(out).use { zin.copyTo(it) }
                if (apk == null && out.name.endsWith(".apk", ignoreCase = true)) apk = out
            }
        }
        zip.delete()
        return apk ?: throw IOException("W archiwum nie ma pliku APK")
    }

    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Zwraca false, gdy trzeba najpierw zezwolić na instalację z tej aplikacji. */
    fun install(ctx: Context, apk: File): Boolean {
        if (!canInstall(ctx)) {
            openInstallPermission(ctx)
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }
}
