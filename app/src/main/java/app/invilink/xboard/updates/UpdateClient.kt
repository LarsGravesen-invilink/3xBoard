package app.invilink.xboard.updates

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

private const val API = "https://api.github.com/repos/LarsGravesen-invilink/3xBoard/releases/latest"
internal const val MAX_APK = 256L * 1024 * 1024

data class UpdateRelease internal constructor(val version: SemVersion, val url: String, val size: Long, val sha256: String)
sealed class UpdateCheck {
    data object UpToDate : UpdateCheck()
    data class Available(val release: UpdateRelease) : UpdateCheck()
}
class UpdateIncompatibleException(message: String) : Exception(message)
class UpdateFormatException(message: String) : Exception(message)
class VerifiedUpdate internal constructor(internal val file: File, internal val release: UpdateRelease)

/** All network/disk methods are suspend functions: invoke from a lifecycle-owned coroutine. */
class UpdateClient(context: Context) {
    private val context = context.applicationContext

    suspend fun check(): UpdateCheck = withContext(Dispatchers.IO) {
        val connection = open(API, api = true)
        val json = try {
            connection.inputStream.use { input ->
                val bytes = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer); if (n < 0) break
                    if (bytes.size() + n > 1024 * 1024) throw UpdateFormatException("Release metadata too large")
                    bytes.write(buffer, 0, n)
                }
                JSONObject(bytes.toString("UTF-8"))
            }
        } finally { connection.disconnect() }
        val release = parseRelease(json)
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        val current = SemVersion.parseInstalled(installed.versionName ?: "") ?: throw UpdateIncompatibleException("Не удалось определить текущую версию приложения")
        if (release.version > current) UpdateCheck.Available(release) else UpdateCheck.UpToDate
    }

    /** onProgress runs on Dispatchers.IO. Cancellation removes the partial file. */
    suspend fun download(release: UpdateRelease, onProgress: (Long, Long) -> Unit = { _, _ -> }): VerifiedUpdate = withContext(Dispatchers.IO) {
        validateRelease(release)
        val dir = File(context.cacheDir, "updates").apply { check(mkdirs() || isDirectory) }
        val file = File(dir, "${UUID.randomUUID()}.apk")
        var success = false
        try {
            val connection = open(release.url, api = false)
            try {
                val advertised = connection.contentLengthLong
                if (advertised >= 0 && advertised != release.size) throw UpdateFormatException("Неверный размер APK")
                val hash = MessageDigest.getInstance("SHA-256")
                var total = 0L
                connection.inputStream.use { input -> file.outputStream().use { output ->
                    val buffer = ByteArray(32768)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer); if (n < 0) break
                        total += n
                        if (total > release.size) throw UpdateFormatException("APK превышает заявленный размер")
                        hash.update(buffer, 0, n); output.write(buffer, 0, n); onProgress(total, release.size)
                    }
                } }
                if (total != release.size || hash.digest().hex() != release.sha256) throw UpdateFormatException("Размер или SHA-256 APK не совпадает")
            } finally { connection.disconnect() }
            currentCoroutineContext().ensureActive()
            verifyArchive(file, release)
            success = true
            VerifiedUpdate(file, release)
        } finally { if (!success) file.delete() }
    }

    /** Revalidate immediately before handing the file to Android's installer. */
    suspend fun installIntent(update: VerifiedUpdate): Intent = withContext(Dispatchers.IO) {
        val file = update.file
        if (file.parentFile?.canonicalFile != File(context.cacheDir, "updates").canonicalFile || !file.isFile)
            throw UpdateIncompatibleException("Файл обновления недоступен")
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(32768)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer); if (n < 0) break
                size += n; if (size > update.release.size) throw UpdateFormatException("Неверный размер APK")
                digest.update(buffer, 0, n)
            }
        }
        if (size != update.release.size || digest.digest().hex() != update.release.sha256) throw UpdateFormatException("APK изменён")
        verifyArchive(file, update.release)
        if (!context.packageManager.canRequestPackageInstalls()) throw UpdateIncompatibleException("Разрешите установку приложений из этого источника")
        Intent(Intent.ACTION_VIEW).setDataAndType(FileProvider.getUriForFile(context, "${context.packageName}.updates", file), "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()
    fun installPermissionIntent(): Intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
    fun discard(update: VerifiedUpdate) { update.file.delete() }

    @Suppress("DEPRECATION")
    private fun verifyArchive(file: File, release: UpdateRelease) {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw UpdateIncompatibleException("APK не удалось прочитать на этом устройстве")
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        if (archive.packageName != context.packageName ||
            !isUpdateVersionCompatible(release.version, archive.versionName, archive.longVersionCode, installed.versionName, installed.longVersionCode) ||
            (archive.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) > android.os.Build.VERSION.SDK_INT)
            throw UpdateIncompatibleException("Несовместимые package, версия или versionCode APK")
        val a = archive.signingInfo?.apkContentsSigners?.map { it.toByteArray().hex() }?.toSet()
        val b = installed.signingInfo?.apkContentsSigners?.map { it.toByteArray().hex() }?.toSet()
        if (a.isNullOrEmpty() || b.isNullOrEmpty() || a != b) throw UpdateIncompatibleException("Подпись APK отличается от установленного приложения")
    }
}

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }

internal fun isUpdateVersionCompatible(
    release: SemVersion, archiveName: String?, archiveCode: Long, installedName: String?, installedCode: Long
): Boolean {
    val installedVersion = SemVersion.parseInstalled(installedName ?: "") ?: return false
    return archiveName == release.text && release > installedVersion && archiveCode >= installedCode
}

internal fun parseRelease(json: JSONObject): UpdateRelease {
    if (json.opt("draft") != false || json.opt("prerelease") != false) throw UpdateFormatException("Релиз не является стабильным")
    val tag = json.opt("tag_name") as? String ?: throw UpdateFormatException("Нет тега")
    val version = SemVersion.parse(tag) ?: throw UpdateFormatException("Некорректный SemVer тега")
    if (version.prerelease.isNotEmpty()) throw UpdateFormatException("Релиз не является стабильным")
    val assets = json.optJSONArray("assets") ?: throw UpdateFormatException("Нет assets")
    val expected = listOf("3xBoard-$tag.apk", "xBoard-$tag.apk")
    val matches = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }.filter { it.opt("name") in expected }
    if (expected.any { name -> matches.count { it.opt("name") == name } > 1 } || matches.isEmpty())
        throw UpdateFormatException("Нужен APK ${expected.joinToString(" или ")}, без дубликатов")
    val asset = matches.firstOrNull { it.opt("name") == expected[0] } ?: matches.single()
    if (asset.opt("state") != "uploaded") throw UpdateFormatException("APK не загружен")
    val sizeValue = asset.opt("size")
    if (sizeValue !is Int && sizeValue !is Long) throw UpdateFormatException("Некорректный размер APK")
    val size = (sizeValue as Number).toLong()
    val digest = asset.opt("digest") as? String ?: throw UpdateFormatException("Нет SHA-256")
    if (!Regex("sha256:[0-9a-f]{64}").matches(digest)) throw UpdateFormatException("Некорректный SHA-256")
    val url = asset.opt("browser_download_url") as? String ?: throw UpdateFormatException("Нет URL APK")
    val name = asset.opt("name") as String
    if (!UpdateUrls.asset(url, tag, name)) throw UpdateFormatException("Некорректный URL APK")
    val result = UpdateRelease(version, url, size, digest.removePrefix("sha256:"))
    validateRelease(result)
    return result
}
internal fun validateRelease(release: UpdateRelease) {
    if (release.version.prerelease.isNotEmpty() || release.size !in 1..MAX_APK || !Regex("[0-9a-f]{64}").matches(release.sha256) ||
        listOf("3xBoard-${release.version.text}.apk", "xBoard-${release.version.text}.apk")
            .none { UpdateUrls.asset(release.url, release.version.text, it) }) throw UpdateFormatException("Некорректный asset")
}

private fun open(address: String, api: Boolean): HttpURLConnection {
    var url = address
    repeat(6) {
        if (if (api) url != API else !UpdateUrls.allowed(url)) throw UpdateFormatException("Недопустимый URL загрузки")
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 15000; c.readTimeout = 15000
        c.setRequestProperty("Accept", if (api) "application/vnd.github+json" else "application/octet-stream")
        c.setRequestProperty("User-Agent", "xBoard-updater")
        try {
            val status = c.responseCode
            if (status in 300..399) {
                val location = c.getHeaderField("Location") ?: throw UpdateFormatException("Redirect без Location")
                if (api) throw UpdateFormatException("Redirect API запрещён")
                url = URL(URL(url), location).toExternalForm()
                c.disconnect()
            } else {
                if (status != 200) throw java.io.IOException("GitHub HTTP $status")
                return c
            }
        } catch (e: Exception) { c.disconnect(); throw e }
    }
    throw UpdateFormatException("Слишком много redirect")
}
