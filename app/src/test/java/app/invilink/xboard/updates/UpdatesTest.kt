package app.invilink.xboard.updates

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdatesTest {
    @Test fun semverPrecedenceAndLegacyInstalledRc() {
        val versions = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0")
        versions.zipWithNext().forEach { (a, b) -> assertTrue("$a < $b", SemVersion.parse(a)!! < SemVersion.parse(b)!!) }
        assertTrue(SemVersion.parse("1.0.1")!! > SemVersion.parseInstalled("1.0.1rc")!!)
        assertEquals(0, SemVersion.parse("1.0.1+abc")!!.compareTo(SemVersion.parse("1.0.1+xyz")!!))
        assertTrue(SemVersion.parse("999999999999999999999.0.0")!! > SemVersion.parse("2.0.0")!!)
    }
    @Test fun rejectsInvalidSemver() {
        listOf("1.0.1rc", "v1.0.1", "01.0.0", "1.2", "1.2.3-01", "1.2.3-", "1.2.3+", " 1.2.3", "1.2.3-rc..1").forEach {
            assertNull(it, SemVersion.parse(it))
        }
        assertNull(SemVersion.parseInstalled("1.0.1rc1"))
    }
    private fun release(name: String = "xBoard-1.0.1.apk"): JSONObject {
        val asset = JSONObject().put("name", name).put("state", "uploaded")
            .put("size", 42).put("digest", "sha256:" + "a".repeat(64))
            .put("browser_download_url", "https://github.com/LarsGravesen-invilink/3xBoard/releases/download/1.0.1/$name")
        return JSONObject().put("draft", false).put("prerelease", false).put("tag_name", "1.0.1").put("assets", JSONArray().put(asset))
    }
    @Test fun validRelease() {
        for (name in listOf("xBoard-1.0.1.apk", "3xBoard-1.0.1.apk")) {
            val parsed = parseRelease(release(name))
            assertEquals(42L, parsed.size)
            assertTrue(parsed.url.endsWith("/$name"))
            validateRelease(parsed)
        }
    }
    @Test fun newAssetIsPreferredWhenBothExist() {
        val both = release().also { it.getJSONArray("assets").put(release("3xBoard-1.0.1.apk").getJSONArray("assets").getJSONObject(0)) }
        assertTrue(parseRelease(both).url.endsWith("/3xBoard-1.0.1.apk"))
    }
    @Test fun malformedAssetsAreRejected() {
        val bad = listOf(
            release().put("tag_name", "1.0.1rc"),
            release().put("draft", true),
            release().put("prerelease", true),
            release().also { it.getJSONArray("assets").getJSONObject(0).put("digest", "sha256:bad") },
            release().also { it.getJSONArray("assets").getJSONObject(0).put("size", 0) },
            release().also { it.getJSONArray("assets").getJSONObject(0).put("size", "42") },
            release().also { it.getJSONArray("assets").getJSONObject(0).put("browser_download_url", "https://evil.example/file") },
            release("3xBoard-1.0.1.apk").also { it.getJSONArray("assets").getJSONObject(0).put("browser_download_url", "https://github.com/LarsGravesen-invilink/3xBoard/releases/download/1.0.1/xBoard-1.0.1.apk") },
            release("3xBoard-1.0.1-debug.apk"),
            release().also { it.getJSONArray("assets").put(it.getJSONArray("assets").getJSONObject(0)) },
            release("3xBoard-1.0.1.apk").also { it.getJSONArray("assets").put(it.getJSONArray("assets").getJSONObject(0)) }
        )
        bad.forEach { value ->
            try { parseRelease(value); fail("accepted $value") } catch (_: UpdateFormatException) { }
        }
    }
    @Test fun archiveVersionPolicyAllowsEqualCodeOnlyForNewerRelease() {
        val digest = "0d19803ee2e669e9d2798f4f034cfc7365806a88a2295745c4a5ffcfff494211"
        val published = parseRelease(release().also { it.getJSONArray("assets").getJSONObject(0).put("digest", "sha256:$digest") })
        val version = published.version
        assertTrue(isUpdateVersionCompatible(version, "1.0.1", 2, "1.0.1rc", 2))
        assertTrue(isUpdateVersionCompatible(version, "1.0.1", 2, "1.0.0", 1))
        assertFalse(isUpdateVersionCompatible(version, "1.0.1", 2, "1.0.0", 3))
        assertFalse(isUpdateVersionCompatible(version, "1.0.1", 2, "1.0.1", 2))
        assertFalse(isUpdateVersionCompatible(version, "1.0.1", 3, "1.0.2", 2))
        assertFalse(isUpdateVersionCompatible(version, "1.0.0", 2, "1.0.0", 2))
        assertFalse(isUpdateVersionCompatible(version, null, 2, "1.0.0", 2))
        assertFalse(isUpdateVersionCompatible(version, "1.0.1", 2, null, 2))
    }
    @Test fun cooldownCoversBackgroundPromptsButExpiresAt24Hours() {
        val day = 24 * 60 * 60 * 1000L
        val declined = 10_000L
        assertFalse(isUpdateCoolingDown(0, declined))
        assertTrue(isUpdateCoolingDown(declined, declined))
        assertTrue(isUpdateCoolingDown(declined, declined + day - 1))
        assertFalse(isUpdateCoolingDown(declined, declined + day))
        assertTrue(isUpdateCoolingDown(declined, declined - 1))
    }
    @Test fun redirectsMustUseExactHttpsHosts() {
        assertTrue(UpdateUrls.allowed("https://release-assets.githubusercontent.com/file?token=1"))
        listOf("http://github.com/a", "https://github.com.evil.example/a", "https://github.com@evil.example/a", "https://github.com:444/a", "https://evil.example/a", "https://objects.githubusercontent.com.evil.example/a").forEach { assertFalse(it, UpdateUrls.allowed(it)) }
        assertFalse(UpdateUrls.asset("https://github.com/LarsGravesen-invilink/3xBoard/releases/download/1.0.1/xBoard-1.0.1.apk?x=1", "1.0.1", "xBoard-1.0.1.apk"))
    }
}
