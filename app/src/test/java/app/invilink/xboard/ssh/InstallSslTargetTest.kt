package app.invilink.xboard.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class InstallSslTargetTest {
    @Test
    fun consoleSilenceTimerRestartsWhenNewOutputArrives() {
        assertEquals(60_000L, consoleSilenceRemaining(10_000, 10_000))
        assertEquals(1L, consoleSilenceRemaining(10_000, 69_999))
        assertEquals(0L, consoleSilenceRemaining(10_000, 70_000))
        assertEquals(60_000L, consoleSilenceRemaining(70_000, 70_000))
        assertEquals(0L, consoleSilenceRemaining(70_000, 150_000))
    }

    @Test
    fun consoleReadsLargeOutputWithoutLineOrLengthTruncation() = kotlinx.coroutines.runBlocking {
        val expected = "x".repeat(400_000) + "\r\n" + "строка 🔑\n".repeat(25_001) + "конец без перевода строки"
        val input = expected.byteInputStream(Charsets.UTF_8)
        val output = StringBuilder()
        readConsoleText(input, { reader, buffer -> reader.read(buffer, 0, minOf(buffer.size, 3)) }) { output.append(it) }
        assertEquals(expected, output.toString())
    }

    @Test
    fun consoleCommandValidationAllowsShellSyntaxAndRejectsInvalidInput() {
        listOf("id", "printf '%s\\n' \"пример🔑\" | head -n 1", "echo first\necho second", "x".repeat(65_536)).forEach {
            org.junit.Assert.assertTrue(isValidConsoleCommand(it))
        }
        listOf("", " \n\t", "echo\u0000x", "\uD800", "x".repeat(65_537)).forEach {
            org.junit.Assert.assertFalse(isValidConsoleCommand(it))
        }
    }

    @Test
    fun sshPasswordsPreserveUnicodeSpecialCharactersAndWhitespace() {
        val passwords = listOf("pass !@#\$%&'\"\\", "пароль🔑", "a\u200Db", " leading and trailing ", "line\nbreak", "\u0000")
        passwords.forEach { password ->
            assertEquals(password, SshCredentials(host = "ssh.example.test", password = password).validate().password)
        }
    }

    @Test
    fun sshSecretsCheckLengthAndMalformedUnicodeWithoutValidatingInactiveFields() {
        val base = SshCredentials(host = "ssh.example.test", password = "a".repeat(4096))
        assertEquals(base.password, base.validate().password)
        assertThrows(IllegalArgumentException::class.java) { base.copy(password = "a".repeat(4097)).validate() }
        assertThrows(IllegalArgumentException::class.java) { base.copy(password = "\uD800").validate() }
        assertThrows(IllegalArgumentException::class.java) { base.copy(password = "").validate() }
        val key = base.copy(authMethod = SshAuthMethod.PRIVATE_KEY, password = "\uD800", privateKey = "test-key", keyPassphrase = "🔑\u200D ")
        assertEquals(key.keyPassphrase, key.validate().keyPassphrase)
        assertThrows(IllegalArgumentException::class.java) { key.copy(keyPassphrase = "\uD800").validate() }
        assertEquals("valid", base.copy(password = "valid", privateKey = "\uD800", keyPassphrase = "\uD800").validate().password)
    }

    @Test
    fun installedPanelWithoutNameCanBeSavedAndRecoveredInMainList() {
        val server = InstalledServer(
            panelId = "install-result", sshHost = "ssh.example.test", sshPort = 22, sshUsername = "root",
            hostKeyAlgorithm = "ssh-ed25519", hostKeyBase64 = "unused-in-this-test",
            panelUsername = "admin", panelPassword = "password", panelPort = 2053,
            panelUrl = "https://panel.example.test:2053/secret/", token = "test-token", secretPath = "secret",
            sslMode = SslMode.DOMAIN, certificateTarget = "panel.example.test",
        )
        assertEquals("panel.example.test", server.panelConfig("").name)
        assertEquals("panel.example.test", server.panelConfig("   ").name)
        assertEquals("Моя панель", server.panelConfig("  Моя панель  ").name)
        assertEquals(server.panelId, server.panelConfig().id)
        assertEquals(server.panelUrl, server.panelConfig().baseUrl)
        assertEquals(server.token, server.panelConfig().token)
    }

    @Test
    fun selectedReleaseUpdatesInstallerVersionAndPinnedHashesTogether() {
        val release = ThreeXRelease("3.12.1", "a".repeat(40), "b".repeat(64), "c".repeat(64),
            "d".repeat(64), "e".repeat(64), "f".repeat(64))
        val transformed = release.applyTo("3.11.0 c621ba9014a6aa21fd7c9ecf45655e44e24ebf13 " +
            "9684bae4f769b2127fcceceb4f200045657381311867529e6ba76c6b58e2825d " +
            "0ddfa4702f595442e2962acd81addc768fdacaa4ff2672c971a0463dae744916")
        assertEquals("3.12.1 ${"a".repeat(40)} ${"b".repeat(64)} ${"e".repeat(64)}", transformed)
    }

    @Test
    fun importDiscoveryScriptHasValidPythonSyntaxWithoutExecutingVpsCommands() {
        val process = ProcessBuilder("python3", "-c", "import ast,sys; ast.parse(sys.stdin.read())")
            .redirectErrorStream(true).start()
        process.outputStream.bufferedWriter().use { it.write(ThreeXPanelImporter.DISCOVER) }
        val diagnostic = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(diagnostic, 0, process.waitFor())
    }

    @Test
    fun releaseSelectedMaintenanceScriptHasValidPythonSyntax() {
        val release = ThreeXRelease("3.12.1", "a".repeat(40), "b".repeat(64), "c".repeat(64),
            "d".repeat(64), "e".repeat(64), "f".repeat(64))
        val script = ThreeXMaintenance.runnerFor(release)
        org.junit.Assert.assertTrue(script.contains("else '3.12.1'"))
        org.junit.Assert.assertTrue(script.contains("HASHES['3.12.1']"))
        val process = ProcessBuilder("python3", "-c", "import ast,sys; ast.parse(sys.stdin.read())")
            .redirectErrorStream(true).start()
        process.outputStream.bufferedWriter().use { it.write(script) }
        val diagnostic = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(diagnostic, 0, process.waitFor())
    }

    @Test
    fun ipCertificateUsesConnectedPublicIpv4() {
        val selected = options(SslMode.IP)
            .forConnectedServer("8.8.8.8")
            .validate()

        assertEquals("8.8.8.8", selected.publicIp)
        assertEquals("8.8.8.8", selected.certificateTarget)
        assertEquals("", selected.domain)
    }

    @Test
    fun hostnameConnectionUsesProvidedPeerAddressAsIpTarget() {
        val credentials = SshCredentials(
            host = "ssh.example.test",
            password = "test-ssh-password",
        ).validate()
        val selected = options(SslMode.IP)
            .forConnectedServer("1.1.1.1")
            .validate()

        assertEquals("ssh.example.test", credentials.host)
        assertEquals("1.1.1.1", selected.certificateTarget)
        assertNotEquals(credentials.host, selected.certificateTarget)
    }

    @Test
    fun ipModeRejectsLoopbackPeers() {
        for (address in listOf("127.0.0.1", "127.1.2.3")) {
            assertIpPeerRejected(address)
        }
    }

    @Test
    fun ipModeRejectsPrivatePeers() {
        for (address in listOf("10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.1.1")) {
            assertIpPeerRejected(address)
        }
    }

    @Test
    fun ipModeRejectsCarrierGradeNatPeers() {
        for (address in listOf("100.64.0.1", "100.127.255.254")) {
            assertIpPeerRejected(address)
        }
    }

    @Test
    fun ipModeRejectsIpv6Peers() {
        for (address in listOf("2606:4700:4700::1111", "::1", "::ffff:8.8.8.8")) {
            assertIpPeerRejected(address)
        }
    }

    @Test
    fun ipModeRejectsMissingPeerRatherThanKeepingStaleIp() {
        assertIpPeerRejected(null)
    }

    @Test
    fun domainModeNeedsNoIpOrConnectedPeer() {
        val selected = options(SslMode.DOMAIN)
            .copy(domain = "panel.example.test", publicIp = "")
            .forConnectedServer(null)
            .validate()

        assertEquals("panel.example.test", selected.certificateTarget)
        assertEquals("", selected.publicIp)
    }

    @Test
    fun domainModeAcceptsPrivateConnectedPeer() {
        for (address in listOf("10.0.0.1", "172.16.0.1", "192.168.1.1")) {
            val selected = options(SslMode.DOMAIN)
                .copy(domain = "panel.example.test")
                .forConnectedServer(address)
                .validate()

            assertEquals("panel.example.test", selected.certificateTarget)
            assertEquals("", selected.publicIp)
        }
    }

    @Test
    fun domainTargetIsTrimmedAndLowercased() {
        val selected = options(SslMode.DOMAIN)
            .copy(domain = "  PaNeL.ExAmPlE.TeSt  ")
            .forConnectedServer(null)
            .validate()

        assertEquals("panel.example.test", selected.domain)
        assertEquals("panel.example.test", selected.certificateTarget)
    }

    @Test
    fun ipModeIgnoresAndClearsInvalidStaleDomain() {
        val selected = options(SslMode.IP)
            .copy(domain = "https://invalid.example.test/path\n")
            .forConnectedServer("8.8.8.8")
            .validate()

        assertEquals("", selected.domain)
        assertEquals("8.8.8.8", selected.certificateTarget)
    }

    @Test
    fun domainModeIgnoresAndClearsInvalidStaleIp() {
        for (address in listOf("not-an-ip\n", "192.168.1.1", "999.1.1.1", "::1")) {
            val selected = options(SslMode.DOMAIN)
                .copy(domain = "panel.example.test", publicIp = address)
                .validate()

            assertEquals("panel.example.test", selected.certificateTarget)
            assertEquals("", selected.publicIp)
        }
    }

    @Test
    fun domainModeKeepsValidHelperIpWithoutUsingItAsCertificateTarget() {
        val selected = options(SslMode.DOMAIN)
            .copy(domain = "certificate.example.test")
            .forConnectedServer("8.8.8.8")
            .validate()

        assertEquals("8.8.8.8", selected.publicIp)
        assertEquals("certificate.example.test", selected.certificateTarget)
        assertNotEquals(selected.publicIp, selected.certificateTarget)
    }

    @Test
    fun freshPeerChangesProduceNewOptionsWithoutMutatingEarlierSelection() {
        val original = options(SslMode.IP).copy(publicIp = "9.9.9.9")
        val first = original.forConnectedServer("8.8.8.8").validate()
        val second = first.forConnectedServer("1.1.1.1").validate()

        assertEquals(original.copy(publicIp = "8.8.8.8"), first)
        assertEquals(original.copy(publicIp = "1.1.1.1"), second)
        assertEquals("9.9.9.9", original.certificateTarget)
        assertEquals("8.8.8.8", first.certificateTarget)
        assertEquals("1.1.1.1", second.certificateTarget)
    }

    @Test
    fun maintenanceOptionsAreUnchangedByConnectedPeer() {
        for (operation in listOf(InstallOperation.UPDATE, InstallOperation.REINSTALL)) {
            for (mode in listOf(SslMode.IP, SslMode.DOMAIN)) {
                val original = options(mode).copy(
                    operation = operation,
                    domain = "existing.example.test",
                    publicIp = "9.9.9.9",
                )
                for (address in listOf("8.8.8.8", "192.168.1.1", "::1", null)) {
                    assertSame(original, original.forConnectedServer(address))
                }
            }
        }
    }

    @Test
    fun panelPasswordAcceptsSixCharactersAndSpecialSymbolsWithoutTrimming() {
        for (password in listOf("a!@#$%", " 'a\"\\$; &<>|`/+=: ", "пароль", " a b c ")) {
            val selected = options(SslMode.DOMAIN).copy(domain = "panel.example.test", panelPassword = password)
            assertEquals(password, selected.validate().panelPassword)
        }
    }

    @Test
    fun panelPasswordRejectsFewerThanSixUnicodeCharacters() {
        for (password in listOf("", "12345", "abcd\uD83D\uDD12")) {
            assertThrows(IllegalArgumentException::class.java) {
                options(SslMode.DOMAIN).copy(domain = "panel.example.test", panelPassword = password).validate()
            }
        }
    }

    @Test
    fun panelPasswordRejectsControlCharactersAndMalformedUnicode() {
        for (password in listOf("abc\u0000def", "abc\ndef", "abc\rdef", "abc\tdef", "abc\u007Fdef", "abc\uD800def")) {
            assertThrows(IllegalArgumentException::class.java) { ServerValidation.panelPassword(password) }
        }
    }

    @Test
    fun panelPasswordEnforcesBcryptByteLimitRatherThanCharacterLimit() {
        for (password in listOf("a".repeat(72), "я".repeat(36), "\uD83D\uDD12".repeat(18))) {
            ServerValidation.panelPassword(password)
        }
        for (password in listOf("a".repeat(73), "я".repeat(37), "\uD83D\uDD12".repeat(19))) {
            assertThrows(IllegalArgumentException::class.java) { ServerValidation.panelPassword(password) }
        }
    }

    @Test
    fun storedPasswordsKeepLegacyLengthSupportAndAcceptNewSymbols() {
        ServerValidation.panelPassword("a".repeat(128), maxBytes = 128)
        ServerValidation.panelPassword("a!@#$%", maxBytes = 128)
        assertThrows(IllegalArgumentException::class.java) {
            ServerValidation.panelPassword("a".repeat(129), maxBytes = 128)
        }
    }

    @Test
    fun symbolicAndUnicodePasswordsRoundTripThroughInstallerBcrypt() {
        for (password in listOf("a!@#$%", " 'a\"\\$; &<>|`/+=: ", "пароль", "\uD83D\uDD12".repeat(6))) {
            ServerValidation.panelPassword(password)
            val chars = password.toCharArray()
            try {
                val hash = org.bouncycastle.crypto.generators.OpenBSDBCrypt.generate("2a", chars, ByteArray(16), 4)
                org.junit.Assert.assertTrue(org.bouncycastle.crypto.generators.OpenBSDBCrypt.checkPassword(hash, chars))
            } finally {
                chars.fill('\u0000')
            }
        }
    }

    @Test
    fun duplicateRecordsShareAnInstallationOnlyAtTheSamePanelEndpointAndPinnedSshServer() {
        val server = installedServer()
        val duplicate = server.copy(panelId = "duplicate", token = "updated-token", panelUrl = server.panelUrl.trimEnd('/'))
        org.junit.Assert.assertTrue(server.sharesInstallationWith(duplicate))
        org.junit.Assert.assertTrue(duplicate.sharesInstallationWith(server))
    }

    @Test
    fun differentPanelsOnTheSameSshServerAreNotLinkedForRemoval() {
        val server = installedServer()
        for (other in listOf(
            server.copy(panelPort = 8443, panelUrl = "https://panel.example.test:8443/selected/"),
            server.copy(secretPath = "other", panelUrl = "https://panel.example.test:2053/other/"),
            server.copy(panelUrl = "https://other.example.test:2053/selected/"),
            server.copy(sshHost = "other-ssh.example.test"),
            server.copy(sshPort = 2222),
            server.copy(hostKeyAlgorithm = "ssh-ed25519"),
            server.copy(hostKeyBase64 = "different-pinned-key"),
        )) {
            org.junit.Assert.assertFalse(server.sharesInstallationWith(other))
            org.junit.Assert.assertFalse(other.sharesInstallationWith(server))
        }
    }

    @org.junit.Test
    fun protectionExcludesEntireSshHostAndPinnedAliases() {
        val one = installedServer()
        val sameHost = one.copy(panelId = "other", sshHost = one.sshHost.uppercase(), hostKeyBase64 = "other-key")
        val alias = one.copy(panelId = "alias", sshHost = "other.example.test")
        val separate = one.copy(panelId = "separate", sshHost = "separate.example.test", hostKeyBase64 = "separate-key")
        val servers = listOf(one, sameHost, alias, separate)
        assertEquals(listOf(separate), protectionServers(servers, setOf(one.panelId)))
        assertEquals(servers, protectionServers(servers, emptySet()))
    }

    @org.junit.Test
    fun protectionSelectsSavedCredentialsForSharedSshHost() {
        val without = installedServer()
        val with = without.copy(panelId = "with-credentials", credentials = SshCredentials(host = without.sshHost))
        val other = without.copy(panelId = "other", sshHost = "other.example.test")
        assertEquals(listOf(with, other), uniqueProtectionServers(listOf(without, with, other)))
    }

    private fun installedServer() = InstalledServer(
        panelId = "selected", sshHost = "ssh.example.test", sshPort = 22, sshUsername = "root",
        hostKeyAlgorithm = "ssh-rsa", hostKeyBase64 = "pinned-key", panelUsername = "admin",
        panelPassword = "a!@#$%", panelPort = 2053, panelUrl = "https://panel.example.test:2053/selected/",
        token = "test-token", secretPath = "selected", sslMode = SslMode.DOMAIN,
        certificateTarget = "panel.example.test",
    )

    private fun options(mode: SslMode): InstallOptions = InstallOptions.create().copy(
        panelPort = "2053",
        sslMode = mode,
    )

    private fun assertIpPeerRejected(address: String?) {
        val selected = options(SslMode.IP)
            .copy(publicIp = "8.8.8.8")
            .forConnectedServer(address)

        assertEquals("", selected.publicIp)
        assertThrows(IllegalArgumentException::class.java) { selected.validate() }
    }
}
