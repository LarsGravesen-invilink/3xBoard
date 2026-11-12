package app.invilink.xboard.ssh

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerPreflightTest {
    @Test fun installationStateDoesNotTreatFailedOrUnprivilegedChecksAsAbsence() {
        assertEquals(PanelInstallationState.PRESENT, parse(output(installation = "present")).installationState)
        assertEquals(PanelInstallationState.ABSENT, parse(output(installation = "absent")).installationState)
        assertEquals(PanelInstallationState.UNKNOWN, parse(output(uid = "1000")).installationState)
        assertEquals(PanelInstallationState.UNKNOWN, parse(output(), exitCode = 1).installationState)
        assertEquals(PanelInstallationState.UNKNOWN, parse(output().replace("install\tabsent", "install\tabsent\nconflict\tcheck:installation")).installationState)
    }
    @Test
    fun protectionInstallErrorsDistinguishRootAndAptFailures() {
        assertTrue(protectionInstallError(10).contains("root"))
        assertTrue(protectionInstallError(14).contains("apt"))
        assertTrue(protectionInstallError(15).contains("iptables"))
    }
    @Test
    fun protectionToolsInstallationIsConditionalAndScoped() {
        val script = installProtectionToolsCommand
        assertTrue(script.contains("command -v iptables"))
        assertTrue(script.contains("command -v ip6tables"))
        assertTrue(script.contains("--no-install-recommends iptables"))
        assertTrue(script.contains("DEBIAN_FRONTEND=noninteractive"))
        assertFalse(script.contains("ufw", ignoreCase = true))
    }
    @Test
    fun webUiRulesAreScopedRecoverableAndProtectSshPort() {
        val dir = Files.createTempDirectory("webui-mock")
        fun executable(name: String, text: String) {
            val file = dir.resolve(name).toFile()
            file.writeText(text)
            assertTrue(file.setExecutable(true))
        }
        executable("id", "#!/bin/sh\necho 0\n")
        val mock = """#!/bin/sh
            file="${'$'}MOCK_DIR/${'$'}{0##*/}.rule"
            shift 2
            case "${'$'}1" in
              -S) exit 0;;
              -C) [ -f "${'$'}file" ] || { echo 'Bad rule (does a matching rule exist in that chain?)' >&2; exit 1; };;
              -I) if [ "${'$'}{0##*/}" = ip6tables ] && [ -f "${'$'}MOCK_DIR/fail6" ]; then exit 3; fi; : > "${'$'}file";;
              -D) rm "${'$'}file";;
              *) exit 2;;
            esac
        """.trimIndent()
        executable("iptables", mock)
        executable("ip6tables", mock)
        fun run(block: Boolean, sshPort: Int = 22): Pair<Int, String> {
            val process = ProcessBuilder("sh", "-c", webUiProtectionCommand(2053, block)).apply {
                environment()["PATH"] = "$dir:" + System.getenv("PATH")
                environment()["MOCK_DIR"] = dir.toString()
                environment()["SSH_CONNECTION"] = "192.0.2.1 12345 192.0.2.2 $sshPort"
                redirectErrorStream(true)
            }.start()
            val output = process.inputStream.bufferedReader().readText().trim()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            return process.exitValue() to output
        }
        assertEquals(17, run(true, 2053).first)
        assertEquals(0 to "OK", run(true))
        assertEquals(0 to "OK", run(true))
        assertEquals(0 to "OK", run(false))
        assertEquals(0 to "OK", run(false))
        Files.createFile(dir.resolve("fail6"))
        assertEquals(13, run(true).first)
        assertTrue(Files.exists(dir.resolve("iptables.rule")))
        assertFalse(Files.exists(dir.resolve("ip6tables.rule")))
        assertEquals(0 to "OK", run(false))
        assertFalse(Files.exists(dir.resolve("iptables.rule")))
        assertFalse(webUiProtectionCommand(2053, true).contains("ufw", ignoreCase = true))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { webUiProtectionCommand(0, true) }
    }
    @Test
    fun cleanUbuntuIsReadyWithAllTechnicalChecks() {
        val result = parse()

        assertTrue(result.ready)
        assertTrue(result.root)
        assertTrue(result.rootChecked)
        assertTrue(result.platformChecked)
        assertTrue(result.supported)
        assertFalse(result.existingInstallation)
        assertEquals(PanelInstallationState.ABSENT, result.installationState)
        assertEquals("Ubuntu 22.04", result.os)
        assertEquals("x86_64", result.architecture)
        assertTrue(result.conflicts.isEmpty())
        assertTrue(result.warnings.isEmpty())
        assertTrue(result.details.contains("2025 МиБ"))
        assertEquals(2053, result.checkedPanelPort)
    }

    @Test
    fun supportedDebianAndArm64AreReady() {
        val result = parse(output(os = "debian", version = "11", arch = "aarch64"))

        assertTrue(result.ready)
        assertEquals("Debian 11", result.os)
        assertEquals("aarch64", result.architecture)
    }

    @Test
    fun occupiedTcpAndUdpOnHttpAndPanelPortsOnlyWarn() {
        for (httpChallenge in listOf(false, true)) {
            val result = parse(
                output(
                    tcpWarnings = listOf("port:tcp/80", "port:tcp/2053"),
                    udpWarnings = listOf("port:udp/80", "port:udp/2053"),
                ),
                needsHttpChallenge = httpChallenge,
            )

            assertTrue(result.ready)
            assertTrue(result.conflicts.isEmpty())
            assertEquals(4, result.warnings.size)
            for (binding in listOf("TCP/80", "TCP/2053", "UDP/80", "UDP/2053")) {
                assertTrue(binding, result.warnings.any { it.contains(binding) })
            }
        }
    }

    @Test
    fun unavailablePortScansOnlyWarn() {
        val result = parse(output(tcpWarnings = listOf("ports-unavailable:tcp"), udpWarnings = listOf("ports-unavailable:udp")))

        assertTrue(result.ready)
        assertTrue(result.conflicts.isEmpty())
        assertEquals(2, result.warnings.size)
        assertTrue(result.warnings.any { it.contains("TCP") })
        assertTrue(result.warnings.any { it.contains("UDP") })
    }

    @Test
    fun existingInstallationRemainsReadyForMaintenance() {
        val result = parse(output(installation = "present"))

        assertTrue(result.existingInstallation)
        assertTrue(result.ready)
        assertTrue(result.conflicts.isEmpty())
        assertEquals(1, result.warnings.size)
        assertTrue(result.warnings.single().contains("существующая установка"))
    }

    @Test
    fun nonRootSessionBlocksEvenWithExistingInstallation() {
        val result = parse(output(uid = "1000", installation = "present"))

        assertFalse(result.ready)
        assertFalse(result.root)
        assertTrue(result.rootChecked)
        assertTrue(result.existingInstallation)
        assertTrue(result.conflicts.any { it.contains("UID 0") })
    }

    @Test
    fun unsupportedOperatingSystemBlocks() {
        val result = parse(output(os = "fedora", version = "42"))

        assertFalse(result.ready)
        assertFalse(result.supported)
        assertTrue(result.platformChecked)
        assertTrue(result.conflicts.any { it.contains("Debian 11+ и Ubuntu 20+") })
    }

    @Test
    fun oldDistributionVersionsBlock() {
        for ((os, version) in listOf("debian" to "10", "ubuntu" to "18.04")) {
            val result = parse(output(os = os, version = version))

            assertFalse(os, result.ready)
            assertFalse(result.supported)
            assertTrue(result.conflicts.isNotEmpty())
        }
    }

    @Test
    fun unsupportedKernelOrArchitectureBlocks() {
        for (stdout in listOf(output(kernel = "Darwin"), output(arch = "riscv64"))) {
            val result = parse(stdout)

            assertFalse(result.ready)
            assertFalse(result.supported)
            assertTrue(result.conflicts.isNotEmpty())
        }
    }

    @Test
    fun degradedSystemdOnlyWarns() {
        val result = parse(output(systemd = "degraded"))

        assertTrue(result.ready)
        assertTrue(result.conflicts.isEmpty())
        assertTrue(result.warnings.single().contains("degraded"))
        assertTrue(result.details.contains("systemd: degraded"))
    }

    @Test
    fun unavailableOrUnreadySystemdBlocks() {
        for (state in listOf("unavailable", "maintenance", "starting", "offline")) {
            val result = parse(output(systemd = state))

            assertFalse(state, result.ready)
            assertTrue(result.conflicts.any { it.contains("systemd как PID 1") })
        }
    }

    @Test
    fun unknownDiskSpaceOnlyWarns() {
        val result = parse(output(disk = "unknown"))

        assertTrue(result.ready)
        assertTrue(result.conflicts.isEmpty())
        assertTrue(result.warnings.single().contains("disk: unknown"))
        assertTrue(result.details.contains("disk: unknown"))
    }

    @Test
    fun diskSpaceBelowOneGiBDoesNotBlock() {
        for (disk in listOf("0", "1", "1048575")) {
            val result = parse(output(disk = disk))

            assertTrue(disk, result.ready)
            assertTrue(result.conflicts.isEmpty())
            assertTrue(result.warnings.isEmpty())
        }
    }

    @Test
    fun malformedDiskValuesFailClosed() {
        for (disk in listOf("-1", "1.5", "1e6", "9223372036854775808", "SECRET_DISK", "")) {
            val result = parse(output(disk = disk))

            assertMalformed(result)
            assertRedacted(result)
        }
    }

    @Test
    fun incompleteResultIncludesLastStageAndCompletionDiagnostic() {
        val result = parse(output().substringBefore("stage\tudp\n"))

        assertFalse(result.ready)
        assertTrue(result.conflicts.any { it.contains("нет маркера завершения") })
        assertTrue(result.conflicts.any { it.contains("Последний этап: локальные TCP-привязки") })
        assertTrue(result.details.contains("этап: локальные TCP-привязки"))
    }

    @Test
    fun nonzeroExitBlocksEvenWithCompleteProtocol() {
        val result = parse(exitCode = 17)

        assertFalse(result.ready)
        assertTrue(result.conflicts.any { it.contains("ненулевой код завершения") })
        assertTrue(result.details.contains("локальные UDP-привязки"))
        assertTrue(result.details.contains("ненулевой код завершения"))
    }

    @Test
    fun nonblankStderrBlocksWithoutExposingRemoteOutput() {
        val result = parse(stderr = "SECRET_REMOTE_DIAGNOSTIC\n")

        assertFalse(result.ready)
        assertTrue(result.conflicts.any { it.contains("получен stderr") })
        assertTrue(result.details.contains("локальные UDP-привязки"))
        assertTrue(result.details.contains("получен stderr"))
        assertRedacted(result)
    }

    @Test
    fun whitespaceOnlyStderrAndCrLfProtocolAreAccepted() {
        val result = parse(output().replace("\n", "\r\n"), stderr = " \n\t")

        assertTrue(result.ready)
        assertTrue(result.conflicts.isEmpty())
    }

    @Test
    fun missingFieldsOrInstallRecordFailClosed() {
        for (record in listOf("uid\t0\n", "disk\t2073600\n", "install\tabsent\n")) {
            val result = parse(output().replace(record, ""))

            assertFalse(result.ready)
            assertTrue(result.conflicts.any { it.contains("отсутствуют обязательные поля") })
            if (record.startsWith("uid")) assertFalse(result.rootChecked)
        }
    }

    @Test
    fun duplicateFieldsFailClosedEvenWhenValuesDiffer() {
        for (replacement in listOf("uid\t0\nuid\t0\n", "uid\t0\nuid\t1000\n")) {
            assertMalformed(parse(output().replace("uid\t0\n", replacement)))
        }
    }

    @Test
    fun duplicateOrInvalidInstallRecordsFailClosed() {
        for (replacement in listOf("install\tabsent\ninstall\tabsent\n", "install\tabsent\ninstall\tpresent\n", "install\tSECRET_INSTALL\n")) {
            val result = parse(output().replace("install\tabsent\n", replacement))

            assertMalformed(result)
            assertRedacted(result)
        }
    }

    @Test
    fun duplicateWarningsAndConflictsFailClosed() {
        assertMalformed(parse(output(tcpWarnings = listOf("port:tcp/80", "port:tcp/80"))))
        assertMalformed(parse(output().replace("stage\tkernel\n", "conflict\ttool:bash\nconflict\ttool:bash\nstage\tkernel\n")))
    }

    @Test
    fun duplicateSkippedOrOutOfOrderStagesFailClosed() {
        val stdout = output()
        for (candidate in listOf(
            stdout.replace("stage\tarch\n", "stage\tarch\nstage\tarch\n"),
            stdout.replace("stage\tarch\n", ""),
            stdout.replace("stage\tuid\nuid\t0\nstage\tarch\narch\tx86_64\n", "stage\tarch\narch\tx86_64\nstage\tuid\nuid\t0\n"),
        )) {
            assertMalformed(parse(candidate))
        }
    }

    @Test
    fun fieldsAndWarningsInWrongStagesFailClosed() {
        assertMalformed(parse(output().replace("kernel\tLinux\nstage\tuid\nuid\t0\n", "kernel\tLinux\nuid\t0\nstage\tuid\n")))
        assertMalformed(parse(output(tcpWarnings = listOf("disk-unknown"))))
        assertMalformed(parse(output(tcpWarnings = listOf("systemd-degraded"))))
        assertMalformed(parse(output(tcpWarnings = listOf("ports-unavailable:udp"))))
    }

    @Test
    fun invalidDuplicateOrEarlyCompletionMarkersFailClosed() {
        val stdout = output()
        for (candidate in listOf(
            stdout.replace("complete\t1\n", "complete\t0\n"),
            stdout + "complete\t1\n",
            stdout.replace("stage\tudp\ncomplete\t1\n", "complete\t1\nstage\tudp\n"),
        )) {
            assertMalformed(parse(candidate))
        }
    }

    @Test
    fun outputAfterCompletionFailsClosedAndIsRedacted() {
        val result = parse(output() + "SECRET_TRAILING_OUTPUT\n")

        assertMalformed(result)
        assertRedacted(result)
    }

    @Test
    fun unknownWarningOrRecordFailsClosedAndIsRedacted() {
        val warning = parse(output(tcpWarnings = listOf("SECRET_WARNING")))
        val record = parse(output().replace("complete\t1\n", "SECRET_RECORD\tSECRET_VALUE\ncomplete\t1\n"))

        for (result in listOf(warning, record)) {
            assertMalformed(result)
            assertRedacted(result)
        }
    }

    @Test
    fun invalidOrUncheckedPortWarningsFailClosed() {
        for (warning in listOf("port:tcp/6666", "port:tcp/65536", "port:tcp/0", "port:tcp/080", "port:tcp/+80", "port:udp/80")) {
            assertMalformed(parse(output(tcpWarnings = listOf(warning))))
        }
        assertMalformed(parse(output(tcpWarnings = listOf("port:tcp/2053")), panelPort = null))
    }

    @Test
    fun invalidFieldValuesAreNeverExposedInUiText() {
        for ((record, replacement) in listOf(
            "uid\t0" to "uid\tSECRET_UID",
            "arch\tx86_64" to "arch\tSECRET_ARCH",
            "os\tubuntu" to "os\tSECRET_OS",
            "version\t22.04" to "version\tSECRET_VERSION",
            "systemd\trunning" to "systemd\tSECRET_STATE",
        )) {
            val result = parse(output().replace(record, replacement))

            assertFalse(result.ready)
            assertRedacted(result)
        }
    }

    @Test
    fun oversizedControlCharacterAndExcessiveRecordsFailClosed() {
        for (candidate in listOf(
            output().replace("os\tubuntu", "os\t" + "SECRET".repeat(100)),
            output().replace("os\tubuntu", "os\tSECRET\u001b"),
            (0..256).joinToString("\n", postfix = "\n") { "SECRET\t$it" } + output(),
        )) {
            val result = parse(candidate)

            assertMalformed(result)
            assertRedacted(result)
        }
    }

    @Test
    fun requiredToolFailureIsActionableAndStageSpecific() {
        val result = parse("stage\ttools\nconflict\ttool:bash\n", exitCode = 1)

        assertFalse(result.ready)
        assertFalse(result.rootChecked)
        assertFalse(result.platformChecked)
        assertTrue(result.conflicts.any { it.contains("обязательная исполняемая команда bash") })
        assertTrue(result.conflicts.any { it.contains("Установите её") })
        assertTrue(result.details.contains("обязательные команды и выполнение bash"))
    }

    @Test
    fun requiredCheckFailureIdentifiesStageWithoutRemoteOutput() {
        val stdout = output().substringBefore("stage\tdisk\n")
            .replace("systemd\trunning\n", "conflict\tcheck:systemd\n")
        val result = parse(stdout, exitCode = 1, stderr = "SECRET_SYSTEMD_ERROR")

        assertFalse(result.ready)
        assertTrue(result.conflicts.any { it.contains("обязательную проверку: готовность systemd") })
        assertTrue(result.details.contains("этап: готовность systemd"))
        assertRedacted(result)
    }

    @Test
    fun checkedPanelPortPreservesTheRequestedPortOnSuccessAndFailure() {
        for (port in listOf(null, 80, 2053, 65535)) {
            for (exitCode in listOf(0, 1)) {
                val result = parse(panelPort = port, exitCode = exitCode)

                assertEquals(port, result.checkedPanelPort)
                assertEquals(exitCode == 0, result.ready)
                if (port == null) assertNull(result.checkedPanelPort)
            }
        }
    }

    @Test
    fun producerContainsNeitherBrokenUnitNorBrokenProcessAwkScan() {
        val field = Class.forName("app.invilink.xboard.ssh.SshPreflightKt").getDeclaredField("PREFLIGHT_SCRIPT")
        field.isAccessible = true
        val script = field.get(null) as String

        assertFalse(script.contains("([.@-]|\$) {"))
        assertFalse(script.contains("systemctl list-unit"))
        assertFalse(script.contains("ps -eo"))
        assertFalse(script.contains("dpkg-query"))
        assertFalse(script.contains("ip -o link"))
        assertFalse(script.contains("tolower(\$1)"))
        assertTrue(script.contains("emit complete 1"))
    }

    @Test
    fun installationRemnantsAreAStaticNonblockingWarning() {
        val result = parse(output(installationWarnings = listOf("installation-remnants")))

        assertTrue(result.ready)
        assertFalse(result.existingInstallation)
        assertTrue(result.conflicts.isEmpty())
        assertEquals(1, result.warnings.size)
        assertTrue(result.warnings.single().contains("остаточные файлы"))
    }

    @Test
    fun installationRemnantsListActualPaths() {
        val stdout = output().replace("stage\ttcp\n", "remnant\t/etc/default/x-ui\nremnant\t/root/cert\nstage\ttcp\n")
        val result = parse(stdout)
        assertTrue(result.ready)
        assertTrue(result.warnings.single().contains("/etc/default/x-ui"))
        assertTrue(result.warnings.single().contains("/root/cert"))
        assertFalse(result.warnings.single().contains("не подтверждают"))
    }

    @Test
    fun installationRemnantsWarningIsStrictlyStageScopedAndCannotBeDuplicated() {
        assertMalformed(parse(output(tcpWarnings = listOf("installation-remnants"))))
        assertMalformed(parse(output(installationWarnings = listOf("installation-remnants", "installation-remnants"))))
        val result = parse(output(installationWarnings = listOf("installation-remnants:SECRET_PATH")))
        assertMalformed(result)
        assertRedacted(result)
    }

    @Test
    fun producerTreatsLogsAndDefaultEnvironmentAsRemnantsAfterUninstall() {
        for (paths in listOf(
            listOf("/var/log/x-ui/access.log", "/etc/default/x-ui"),
            listOf("/var/log/x-ui/error.log", "/etc/default/x-ui", "/etc/sysconfig/x-ui"),
        )) {
            InstallationSandbox().use { sandbox ->
                paths.forEach { sandbox.file(it) }
                sandbox.assertRemnants()
            }
        }
    }

    @Test
    fun producerTransitionsFromInstalledToRemnantsAfterCoreRemoval() {
        InstallationSandbox().use { sandbox ->
            for (path in listOf(
                "/usr/local/x-ui/x-ui", "/etc/x-ui/x-ui.db", "/etc/systemd/system/x-ui.service",
                "/etc/default/x-ui", "/var/log/x-ui/access.log", "/root/cert/example/fullchain.pem",
                "/root/.acme.sh/acme.sh", "/root/.xboard-install/history/job/result",
                "/usr/local/x-ui-linux-amd64.tar.gz",
            )) sandbox.file(path)
            sandbox.assertInstalled("before core removal")

            for (path in listOf(
                "/usr/local/x-ui/x-ui", "/usr/local/x-ui", "/etc/x-ui/x-ui.db", "/etc/x-ui",
                "/etc/systemd/system/x-ui.service",
            )) sandbox.remove(path)

            sandbox.assertRemnants()
        }
    }

    @Test
    fun producerTreatsCertificatesAcmeHistoryAndArchivesAsRemnants() {
        for (path in listOf(
            "/root/cert/example/fullchain.pem",
            "/root/.acme.sh/acme.sh",
            "/root/.xboard-install/history/job/result",
            "/usr/local/x-ui-linux-amd64.tar.gz",
            "/usr/local/x-ui-linux-arm64.tar.gz",
        )) {
            InstallationSandbox().use { sandbox ->
                sandbox.file(path, executable = true)
                sandbox.assertRemnants()
            }
        }
    }

    @Test
    fun producerDoesNotInferInstallationFromEmptyTreesOrMetadata() {
        for (alias in listOf("x-ui", "3x-ui", "3xui", "xui")) {
            InstallationSandbox().use { sandbox ->
                for (prefix in listOf("/usr/local", "/etc", "/opt", "/var/lib", "/root")) {
                    sandbox.directory("$prefix/$alias")
                }
                sandbox.file("/usr/local/$alias/README")
                sandbox.file("/etc/$alias/settings.json")
                sandbox.file("/usr/bin/$alias")
                sandbox.file("/etc/default/$alias")
                sandbox.file("/etc/sysconfig/$alias")
                sandbox.file("/var/log/$alias/access.log")
                sandbox.file("/usr/local/$alias/logs/access.log", executable = true)
                sandbox.file("/usr/local/$alias/cert/renew.sh", executable = true)
                sandbox.file("/usr/local/$alias/history/old.db")
                sandbox.file("/usr/local/$alias/archive/x-ui.db")
                sandbox.assertRemnants()
            }
        }
    }

    @Test
    fun producerIgnoresDropinsAndDanglingServiceLinks() {
        for (alias in listOf("x-ui", "3x-ui", "3xui", "xui")) {
            InstallationSandbox().use { sandbox ->
                sandbox.file("/etc/systemd/system/$alias.service.d/override.conf")
                sandbox.file("/run/systemd/system/$alias@.service.d/override.conf")
                sandbox.file("/usr/lib/systemd/system/$alias@main.service.d/override.conf")
                sandbox.link("/etc/systemd/system/multi-user.target.wants/$alias.service", "/missing/$alias.service")
                sandbox.link("/run/systemd/system/default.target.requires/$alias@main.service", "/missing/$alias@main.service")
                sandbox.link("/etc/systemd/system/$alias.service", "/missing/$alias.service")
                sandbox.assertRemnants()
            }
        }
    }

    @Test
    fun producerTreatsStandaloneCommandsAsRemnantsEvenWhenExecutable() {
        for (alias in listOf("x-ui", "3x-ui", "3xui", "xui")) {
            for (directory in listOf("/usr/bin", "/usr/local/bin", "/usr/sbin", "/usr/local/sbin", "/bin", "/sbin")) {
                for (executable in listOf(false, true)) {
                    InstallationSandbox().use { sandbox ->
                        sandbox.file("$directory/$alias", executable = executable)
                        sandbox.assertRemnants()
                    }
                }
            }
        }
    }

    @Test
    fun producerDetectsCoreBinariesDatabasesAndCustomExecutableTreesWithoutRunningThem() {
        for (alias in listOf("x-ui", "3x-ui", "3xui", "xui")) {
            for (path in listOf(
                "/usr/local/$alias/$alias",
                "/etc/$alias/$alias.db",
                "/var/lib/$alias/state/panel.db",
                "/opt/$alias/bin/custom-panel",
                "/usr/local/$alias/bin/custom-panel",
                "/etc/$alias.db",
            )) {
                InstallationSandbox().use { sandbox ->
                    sandbox.file(path, executable = !path.endsWith(".db"))
                    sandbox.assertInstalled(path)
                }
            }
        }
        InstallationSandbox().use { sandbox ->
            sandbox.file("/usr/local/x-ui/x-ui")
            sandbox.assertInstalled("non-executable core binary still exists")
        }
    }

    @Test
    fun producerDetectsRealServiceSourcesIncludingTemplatesAliasesAndLinkedUnits() {
        for (prefix in listOf(
            "/etc/systemd/system", "/lib/systemd/system", "/usr/lib/systemd/system",
            "/usr/local/lib/systemd/system", "/run/systemd/system", "/run/systemd/transient",
            "/run/systemd/generator", "/run/systemd/generator.early", "/run/systemd/generator.late",
        )) {
            for (unit in listOf("x-ui.service", "3x-ui@.service", "3xui@main.service", "xui.service")) {
                InstallationSandbox().use { sandbox ->
                    sandbox.file("$prefix/$unit")
                    sandbox.assertInstalled("$prefix/$unit")
                }
            }
        }
        for (alias in listOf("x-ui", "3x-ui", "3xui", "xui")) {
            InstallationSandbox().use { sandbox ->
                sandbox.file("/etc/init.d/$alias")
                sandbox.assertInstalled("init script $alias")
            }
            for (path in listOf(
                "/etc/systemd/system/$alias.service",
                "/etc/systemd/system/multi-user.target.wants/$alias.service",
                "/etc/systemd/system/default.target.requires/$alias@main.service",
            )) {
                InstallationSandbox().use { sandbox ->
                    sandbox.file("/opt/custom/panel.service")
                    sandbox.link(path, "/opt/custom/panel.service")
                    sandbox.assertInstalled(path)
                }
            }
        }
    }

    @Test
    fun producerDetectsLoadedOrActiveServicesButNotUnloadedHistory() {
        for ((load, active) in listOf(
            "loaded" to "inactive", "loaded" to "failed", "masked" to "inactive",
            "bad-setting" to "inactive", "error" to "inactive", "not-found" to "active",
            "not-found" to "activating", "not-found" to "reloading", "not-found" to "deactivating",
        )) {
            InstallationSandbox().use { sandbox ->
                val result = sandbox.detect(load, active)
                assertTrue("$load/$active", result.ready)
                assertTrue("$load/$active", result.existingInstallation)
            }
        }
        for (active in listOf("inactive", "failed")) {
            InstallationSandbox().use { sandbox ->
                val result = sandbox.detect("not-found", active)
                assertTrue(result.ready)
                assertFalse(result.existingInstallation)
                assertTrue(result.warnings.isEmpty())
            }
        }
    }

    @Test
    fun legacyInstallationProtocolWithoutReleaseRemainsAccepted() {
        for (installation in listOf("present", "absent")) {
            val result = parse(output(installation = installation))

            assertTrue(result.ready)
            assertEquals(installation == "present", result.existingInstallation)
            assertNull(result.installedVersion)
        }
    }

    @Test
    fun knownReleaseRecordsPopulateInstalledVersion() {
        for (release in listOf("3.10.0", "3.11.0")) {
            val result = parse(output(installation = "present", release = release))

            assertTrue(result.ready)
            assertTrue(result.existingInstallation)
            assertEquals(release, result.installedVersion)
            assertEquals(parse(output(installation = "present")).warnings, result.warnings)
        }
    }

    @Test
    fun unknownReleaseDoesNotAffectReadiness() {
        val result = parse(output(installation = "present", release = "unknown"))

        assertTrue(result.ready)
        assertTrue(result.existingInstallation)
        assertNull(result.installedVersion)
        assertTrue(result.conflicts.isEmpty())
        assertEquals(parse(output(installation = "present")).warnings, result.warnings)
    }

    @Test
    fun absentInstallationNeverHasInstalledVersion() {
        for (release in listOf("unknown", "3.10.0", "3.11.0")) {
            val result = parse(output(release = release))

            assertTrue(result.ready)
            assertFalse(result.existingInstallation)
            assertNull(result.installedVersion)
        }
    }

    @Test
    fun duplicateReleaseRecordsFailClosedEvenWhenDifferent() {
        for ((first, second) in listOf(
            "3.10.0" to "3.10.0", "3.10.0" to "3.11.0",
            "unknown" to "3.11.0", "3.11.0" to "unknown", "unknown" to "unknown",
        )) {
            val result = parse(output(installation = "present", release = first)
                .replace("release\t$first\n", "release\t$first\nrelease\t$second\n"))

            assertMalformed(result)
            assertNull(result.installedVersion)
        }
    }

    @Test
    fun releaseRequiresAlreadyParsedInstallationInCorrectStage() {
        val stdout = output(installation = "present")
        for (candidate in listOf(
            stdout.replace("install\tpresent\n", "release\t3.11.0\ninstall\tpresent\n"),
            stdout.replace("stage\tinstallation\n", "release\t3.11.0\nstage\tinstallation\n"),
            stdout.replace("stage\ttcp\n", "stage\ttcp\nrelease\t3.11.0\n"),
            stdout.replace("install\tpresent\n", "release\t3.11.0\n"),
            stdout.replace("install\tpresent\n", "install\tSECRET_INVALID\nrelease\t3.11.0\n"),
            stdout + "release\t3.11.0\n",
        )) {
            val result = parse(candidate)
            assertMalformed(result)
            assertNull(result.installedVersion)
            assertRedacted(result)
        }
    }

    @Test
    fun malformedReleaseIsRejectedAndRedacted() {
        for (release in listOf(
            "", "3.10", "3.11", "v3.11.0", "3.12.0", "UNKNOWN", " 3.11.0", "3.11.0 ",
            "/usr/local/x-ui/3.11.0", "xray-3.11.0", "3.11.0;SECRET_COMMAND",
            "SECRET_RELEASE", "3.11.0\tSECRET_FIELD", "3.11.0\nSECRET_RECORD", "SECRET".repeat(100),
        )) {
            val result = parse(output(installation = "present", release = release))
            assertMalformed(result)
            assertNull(result.installedVersion)
            assertRedacted(result)
        }
    }

    @Test
    fun unconfirmedReportsDoNotExposeInstalledVersion() {
        val stdout = output(installation = "present", release = "3.11.0")
        for (result in listOf(
            parse(stdout, exitCode = 1),
            parse(stdout, stderr = "SECRET_REMOTE_ERROR"),
            parse(stdout.substringBefore("complete\t1\n")),
            parse(stdout.replace("stage\tarch\n", "stage\tarch\nstage\tarch\n")),
        )) {
            assertFalse(result.ready)
            assertNull(result.installedVersion)
            assertRedacted(result)
        }
    }

    @Test
    fun producerMapsOnlyFourVerifiedCanonicalBinaryChecksums() {
        for ((hash, release) in verifiedBinaryHashes) {
            InstallationSandbox().use { sandbox ->
                sandbox.file("/usr/local/x-ui/x-ui", executable = true)
                val result = sandbox.detect(checksum = "$hash  @binary")

                assertTrue(hash, result.ready)
                assertTrue(hash, result.existingInstallation)
                assertEquals(hash, release, result.installedVersion)
                assertTrue(result.conflicts.isEmpty())
                assertRedacted(result)
            }
        }
    }

    @Test
    fun producerUnknownMalformedOrFailedChecksumsRemainReady() {
        val known = verifiedBinaryHashes.last().first
        for ((checksum, exitCode) in listOf(
            "${"0".repeat(64)}  @binary" to 0,
            "" to 0,
            "SECRET_HASH" to 0,
            "$known  /etc/x-ui/x-ui.db" to 0,
            "$known  @binary\nrelease\t3.10.0" to 0,
            "$known  @binary" to 1,
            "$known  @binary" to 127,
        )) {
            InstallationSandbox().use { sandbox ->
                sandbox.file("/usr/local/x-ui/x-ui", executable = true)
                val result = sandbox.detect(checksum = checksum, checksumExitCode = exitCode)

                assertTrue(result.ready)
                assertTrue(result.existingInstallation)
                assertNull(result.installedVersion)
                assertTrue(result.conflicts.isEmpty())
                assertRedacted(result)
            }
        }
    }

    @Test
    fun producerRefusesSymlinkBinaryAndCoretreeOnlyForVersionProbe() {
        val checksum = "${verifiedBinaryHashes.last().first}  @binary"
        InstallationSandbox().use { sandbox ->
            sandbox.file("/opt/native/x-ui", executable = true)
            sandbox.link("/usr/local/x-ui/x-ui", "/opt/native/x-ui")
            val result = sandbox.detect(checksum = checksum)

            assertTrue(result.ready)
            assertTrue(result.existingInstallation)
            assertNull(result.installedVersion)
        }
        InstallationSandbox().use { sandbox ->
            sandbox.file("/opt/native/x-ui", executable = true)
            sandbox.link("/usr/local/x-ui", "/opt/native")
            val result = sandbox.detect(checksum = checksum)

            assertTrue(result.ready)
            assertTrue(result.existingInstallation)
            assertNull(result.installedVersion)
        }
    }

    @Test
    fun producerDoesNotInferReleaseFromDatabaseServiceAliasOrMetadata() {
        for (path in listOf(
            "/etc/x-ui/x-ui.db", "/etc/systemd/system/x-ui.service", "/usr/local/3x-ui/3x-ui",
            "/usr/local/x-ui/bin/xray-3.11.0", "/usr/local/x-ui/release-3.11.0.txt",
        )) {
            InstallationSandbox().use { sandbox ->
                sandbox.file(path, executable = true, content = "3.11.0\nSECRET_NOT_PANEL_VERSION\n")
                val result = sandbox.detect(checksum = "${verifiedBinaryHashes.last().first}  @binary")

                assertTrue(path, result.ready)
                assertTrue(path, result.existingInstallation)
                assertNull(path, result.installedVersion)
                assertRedacted(result)
            }
        }
    }

    @Test
    fun producerDoesNotInferReleaseFromStandaloneCommand() {
        InstallationSandbox().use { sandbox ->
            sandbox.file("/usr/local/bin/x-ui", executable = true, content = "3.11.0\nSECRET_NOT_PANEL_VERSION\n")
            val result = sandbox.detect(checksum = "${verifiedBinaryHashes.last().first}  @binary")
            assertTrue(result.ready)
            assertFalse(result.existingInstallation)
            assertNull(result.installedVersion)
            assertEquals(1, result.warnings.size)
            assertTrue(result.warnings.single().contains("остаточные файлы"))
            assertRedacted(result)
        }
    }

    @Test
    fun producerDoesNotHashAbsentOrEmptyCanonicalTrees() {
        for (emptyTree in listOf(false, true)) {
            InstallationSandbox().use { sandbox ->
                if (emptyTree) sandbox.directory("/usr/local/x-ui")
                val result = sandbox.detect(checksum = "${verifiedBinaryHashes.last().first}  @binary")

                assertTrue(result.ready)
                assertFalse(result.existingInstallation)
                assertNull(result.installedVersion)
            }
        }
    }

    private val verifiedBinaryHashes = listOf(
        "c79d4a9fe66838fc692a373a4e9b838d6858d9e7072aa57fe62fd2e0c52f8611" to "3.10.0",
        "1c4f02618053c8b70309edcafea115d1f6f9bff1581f1474be9e5d008838c143" to "3.10.0",
        "fb7b3776334fce69299f90168c8c31377b69f89c7787088e6dbf561d07b3d345" to "3.11.0",
        "053f4c0da86d6b6aff41af2aa7b579a62ebc3105ef5eac77fee5b79ab9a2a8a4" to "3.11.0",
    )

    private inner class InstallationSandbox : java.io.Closeable {
        private val root = Files.createTempDirectory("xboard-preflight-")

        fun directory(path: String): Path = Files.createDirectories(resolve(path))

        fun remove(path: String) = Files.delete(resolve(path))

        fun file(path: String, executable: Boolean = false, content: String? = null) {
            val target = resolve(path)
            Files.createDirectories(target.parent)
            Files.write(target, (content ?: "#!/bin/sh\nprintf 'SECRET_INSTALLED_BINARY_EXECUTED'\n: > '$root/installed-binary-executed'\nexit 97\n").toByteArray())
            if (executable) assertTrue(target.toFile().setExecutable(true, false))
        }

        fun link(path: String, target: String) {
            val link = resolve(path)
            Files.createDirectories(link.parent)
            Files.createSymbolicLink(link, resolve(target))
        }

        fun assertRemnants() {
            val result = detect()
            assertTrue(result.ready)
            assertFalse(result.existingInstallation)
            assertNull(result.installedVersion)
            assertTrue(result.conflicts.isEmpty())
            assertEquals(1, result.warnings.size)
            assertTrue(result.warnings.single().contains("остаточные файлы"))
            assertRedacted(result)
        }

        fun assertInstalled(fixture: String) {
            val result = detect()
            assertTrue(fixture, result.ready)
            assertTrue(fixture, result.existingInstallation)
            assertNull(fixture, result.installedVersion)
            assertTrue(fixture, result.conflicts.isEmpty())
            assertEquals(fixture, 1, result.warnings.size)
            assertTrue(fixture, result.warnings.single().contains("существующая установка"))
            assertRedacted(result)
        }

        fun detect(
            loadState: String = "not-found",
            activeState: String = "inactive",
            checksum: String? = null,
            checksumExitCode: Int = 0,
        ): ServerPreflight {
            val field = Class.forName("app.invilink.xboard.ssh.SshPreflightKt").getDeclaredField("PREFLIGHT_SCRIPT")
            field.isAccessible = true
            val block = (field.get(null) as String)
                .substringAfter("emit stage installation\n")
                .substringBefore("for protocol in tcp udp; do")
            assertTrue(block.contains("emit install"))
            val sandboxed = block.replace(Regex("(?<![A-Za-z0-9_/])/(?:usr|etc|var|opt|root|lib|run|bin|sbin)/")) {
                "$root${it.value}"
            }
            val checksumTool = checksum?.let {
                val response = it.replace("@binary", resolve("/usr/local/x-ui/x-ui").toString()).replace("'", "'\\''")
                """
                    sha256sum() {
                        [ "${'$'}#" = 2 ] && [ "${'$'}1" = -- ] && [ "${'$'}2" = '$root/usr/local/x-ui/x-ui' ] || return 99
                        printf '%s\n' '$response'
                        if [ $checksumExitCode != 0 ]; then printf '%s\n' 'SECRET_CHECKSUM_ERROR' >&2; fi
                        return $checksumExitCode
                    }
                """.trimIndent()
            }.orEmpty()
            val script = """
                emit() { printf '%s\t%s\n' "${'$'}1" "${'$'}2"; }
                fail() { emit conflict "check:${'$'}1"; exit 1; }
                systemctl() { printf '%s\n' 'LoadState=$loadState' 'ActiveState=$activeState'; }
            """.trimIndent() + "\n" + checksumTool + "\n" + sandboxed
            val before = Files.walk(root).use { it.toArray().toSet() }
            val process = ProcessBuilder("/bin/sh", "-s").start()
            try {
                process.outputStream.bufferedWriter().use { it.write(script) }
                assertTrue("installation probe timed out", process.waitFor(10, TimeUnit.SECONDS))
                val stdout = process.inputStream.bufferedReader().use { it.readText() }
                val stderr = process.errorStream.bufferedReader().use { it.readText() }
                assertEquals("installation probe wrote files", before, Files.walk(root).use { it.toArray().toSet() })
                assertEquals(stderr, 0, process.exitValue())
                assertEquals("", stderr)
                assertTrue("release must follow install", stdout.startsWith("install\tpresent\nrelease\t") || stdout.startsWith("install\tabsent\nrelease\t"))
                assertEquals(1, stdout.lineSequence().count { it.startsWith("release\t") })
                return parse(output().replace("install\tabsent\n", stdout))
            } finally {
                process.destroyForcibly()
            }
        }

        private fun resolve(path: String): Path = root.resolve(path.removePrefix("/"))

        override fun close() {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private fun parse(
        stdout: String = output(),
        panelPort: Int? = 2053,
        needsHttpChallenge: Boolean = false,
        exitCode: Int = 0,
        stderr: String = "",
    ): ServerPreflight = parseSshPreflight(SshCommandResult(exitCode, stdout, stderr), panelPort, needsHttpChallenge)

    private fun output(
        uid: String = "0",
        kernel: String = "Linux",
        arch: String = "x86_64",
        os: String = "ubuntu",
        version: String = "22.04",
        systemd: String = "running",
        disk: String = "2073600",
        installation: String = "absent",
        release: String? = null,
        installationWarnings: List<String> = emptyList(),
        tcpWarnings: List<String> = emptyList(),
        udpWarnings: List<String> = emptyList(),
    ): String = buildList {
        add("stage\ttools")
        add("stage\tkernel")
        add("kernel\t$kernel")
        add("stage\tuid")
        add("uid\t$uid")
        add("stage\tarch")
        add("arch\t$arch")
        add("stage\tos")
        add("os\t$os")
        add("version\t$version")
        add("stage\tsystemd")
        add("systemd\t$systemd")
        if (systemd == "degraded") add("warning\tsystemd-degraded")
        add("stage\tdisk")
        add("disk\t$disk")
        if (disk == "unknown") add("warning\tdisk-unknown")
        add("stage\tinstallation")
        add("install\t$installation")
        if (release != null) add("release\t$release")
        installationWarnings.forEach { add("warning\t$it") }
        add("stage\ttcp")
        tcpWarnings.forEach { add("warning\t$it") }
        add("stage\tudp")
        udpWarnings.forEach { add("warning\t$it") }
        add("complete\t1")
    }.joinToString("\n", postfix = "\n")

    private fun assertMalformed(result: ServerPreflight) {
        assertFalse(result.ready)
        assertTrue(result.conflicts.any { it.contains("некорректный протокол") })
    }

    private fun assertRedacted(result: ServerPreflight) {
        val uiText = listOfNotNull(result.os, result.architecture, result.details, result.installedVersion) + result.warnings + result.conflicts
        assertFalse(uiText.any { it.contains("SECRET") })
    }
}
