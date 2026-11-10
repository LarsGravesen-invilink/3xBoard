package app.invilink.xboard.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.io.ByteArrayOutputStream

internal data class ThreeXRelease(
    val version: String,
    val commit: String,
    val installerHash: String,
    val menuHash: String,
    val serviceHash: String,
    val amd64Hash: String,
    val arm64Hash: String,
) {
    fun applyTo(script: String): String = script
        .replace("c621ba9014a6aa21fd7c9ecf45655e44e24ebf13", commit)
        .replace("9684bae4f769b2127fcceceb4f200045657381311867529e6ba76c6b58e2825d", installerHash)
        .replace("26c229d5fb3b184e81581b4b223b801582ca7cf5908aaedeb8d1c7c70ad4cc25", menuHash)
        .replace("95cb6e39499601148404cadf3b4d75b0489301ff7e7ab565cd9db21d23be957c", serviceHash)
        .replace("0ddfa4702f595442e2962acd81addc768fdacaa4ff2672c971a0463dae744916", amd64Hash)
        .replace("f50b4c30fd919d018f2f4c3d8db53a4ca027503d581328727770afb116a79d90", arm64Hash)
        .replace("3.11.0", version)
}

internal object ThreeXReleases {
    private const val API = "https://api.github.com/repos/MHSanaei/3x-ui"
    private val hashPattern = Regex("[0-9a-f]{64}")

    suspend fun latest(): ThreeXRelease = withContext(Dispatchers.IO) {
        val release = JSONObject(fetch("$API/releases/latest").toString(Charsets.UTF_8))
        val tag = release.getString("tag_name")
        require(Regex("v[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}").matches(tag) &&
            !release.getBoolean("draft") && !release.getBoolean("prerelease"))
        val commit = JSONObject(fetch("$API/commits/$tag").toString(Charsets.UTF_8)).getString("sha")
        require(Regex("[0-9a-f]{40}").matches(commit))
        val assets = release.getJSONArray("assets")
        fun archiveHash(arch: String): String {
            val name = "x-ui-linux-$arch.tar.gz"
            val matches = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.getString("name") == name }
            require(matches.size == 1)
            val asset = matches.single()
            require(asset.getString("browser_download_url") == "https://github.com/MHSanaei/3x-ui/releases/download/$tag/$name")
            return asset.getString("digest").removePrefix("sha256:").also { require(hashPattern.matches(it)) }
        }
        coroutineScope {
            suspend fun sourceHash(name: String): String = MessageDigest.getInstance("SHA-256")
                .digest(fetch("https://raw.githubusercontent.com/MHSanaei/3x-ui/$commit/$name"))
                .joinToString("") { "%02x".format(it) }
            val installer = async(Dispatchers.IO) { sourceHash("install.sh") }
            val menu = async(Dispatchers.IO) { sourceHash("x-ui.sh") }
            val service = async(Dispatchers.IO) { sourceHash("x-ui.service.debian") }
            ThreeXRelease(tag.removePrefix("v"), commit, installer.await(), menu.await(), service.await(), archiveHash("amd64"), archiveHash("arm64"))
        }
    }

    private fun fetch(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", "xBoard-release-check")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            check(connection.responseCode == 200) { "Не удалось получить официальный релиз 3x-UI. Повторите проверку версии." }
            return connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= 2_097_152)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }
}
