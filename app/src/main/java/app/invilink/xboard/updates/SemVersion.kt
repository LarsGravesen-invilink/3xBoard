package app.invilink.xboard.updates

import java.math.BigInteger
import java.net.URI

/** SemVer 2.0 precedence; build metadata does not affect ordering. */
data class SemVersion private constructor(
    val major: BigInteger, val minor: BigInteger, val patch: BigInteger,
    val prerelease: List<String>, val text: String
) : Comparable<SemVersion> {
    override fun compareTo(other: SemVersion): Int {
        major.compareTo(other.major).takeIf { it != 0 }?.let { return it }
        minor.compareTo(other.minor).takeIf { it != 0 }?.let { return it }
        patch.compareTo(other.patch).takeIf { it != 0 }?.let { return it }
        if (prerelease.isEmpty() || other.prerelease.isEmpty())
            return when { prerelease.isEmpty() && other.prerelease.isEmpty() -> 0; prerelease.isEmpty() -> 1; else -> -1 }
        for (i in 0 until minOf(prerelease.size, other.prerelease.size)) {
            val a = prerelease[i]; val b = other.prerelease[i]
            val an = a.all { it in '0'..'9' }; val bn = b.all { it in '0'..'9' }
            val c = when { an && bn -> BigInteger(a).compareTo(BigInteger(b)); an -> -1; bn -> 1; else -> a.compareTo(b) }
            if (c != 0) return c
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }
    companion object {
        private val pattern = Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$")
        fun parse(value: String): SemVersion? {
            val m = pattern.matchEntire(value) ?: return null
            val pre = m.groupValues[4].let { if (it.isEmpty()) emptyList() else it.split('.') }
            if (pre.any { it.all { c -> c in '0'..'9' } && it.length > 1 && it[0] == '0' }) return null
            return SemVersion(BigInteger(m.groupValues[1]), BigInteger(m.groupValues[2]), BigInteger(m.groupValues[3]), pre, value)
        }
        /** Only historical installed versions use the non-SemVer suffix rc. */
        fun parseInstalled(value: String): SemVersion? = parse(value) ?: Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)rc$")
            .matchEntire(value)?.let { parse("${it.groupValues[1]}.${it.groupValues[2]}.${it.groupValues[3]}-rc") }
    }
}

// A backwards wall-clock change conservatively extends the suppression window.
internal fun isUpdateCoolingDown(declinedAt: Long, now: Long): Boolean =
    declinedAt > 0L && (now < declinedAt || now - declinedAt < 24 * 60 * 60 * 1000L)

internal object UpdateUrls {
    private val hosts = setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com", "github-releases.githubusercontent.com")
    fun allowed(value: String): Boolean = try {
        val u = URI(value)
        u.scheme == "https" && u.host in hosts && u.rawUserInfo == null && (u.port == -1 || u.port == 443) && u.rawFragment == null
    } catch (_: Exception) { false }
    fun asset(value: String, tag: String, name: String): Boolean = try {
        val u = URI(value)
        allowed(value) && u.host == "github.com" && u.rawQuery == null &&
            u.path == "/LarsGravesen-invilink/3xBoard/releases/download/$tag/$name" &&
            !u.rawPath.contains('%')
    } catch (_: Exception) { false }
}
