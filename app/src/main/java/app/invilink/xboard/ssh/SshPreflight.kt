package app.invilink.xboard.ssh

internal suspend fun runSshPreflight(
    connection: SshConnection,
    panelPort: Int?,
    needsHttpChallenge: Boolean,
): ServerPreflight {
    require(panelPort == null || panelPort in 1..65535) { "Порт панели должен быть от 1 до 65535." }
    val script = (
        "panel_port=${panelPort ?: 0}\n" + PREFLIGHT_SCRIPT
    ).toByteArray(Charsets.UTF_8)
    val result = try {
        connection.executeInput(
            "env -i HOME=/root LC_ALL=C PATH=/usr/sbin:/usr/bin:/sbin:/bin /bin/sh -s",
            script,
            timeoutMillis = 30_000,
            maxBytes = 262_144,
        )
    } finally {
        script.fill(0)
    }
    return parseSshPreflight(result, panelPort, needsHttpChallenge)
}

internal fun parseSshPreflight(
    result: SshCommandResult,
    panelPort: Int?,
    needsHttpChallenge: Boolean,
): ServerPreflight {
    require(panelPort == null || panelPort in 1..65535)
    val fields = linkedMapOf<String, String>()
    val conflicts = linkedSetOf<String>()
    val warnings = linkedSetOf<String>()
    val remnants = linkedSetOf<String>()
    val seen = hashSetOf<String>()
    var stageIndex = -1
    var installation: Boolean? = null
    var release: String? = null
    var complete = false
    var malformed = false
    var records = 0
    for (line in result.stdout.lineSequence()) {
        if (line.isEmpty()) continue
        if (++records > 256) {
            malformed = true
            break
        }
        val parts = line.split('\t')
        if (complete || parts.size != 2 || parts.any { part ->
                part.isEmpty() || part.length > 512 || part.any { it.isISOControl() }
            }
        ) {
            malformed = true
            continue
        }
        val (name, value) = parts
        if (!seen.add("$name\t$value")) {
            malformed = true
            continue
        }
        val stage = PREFLIGHT_STAGES.getOrNull(stageIndex)
        when (name) {
            "stage" -> {
                if (PREFLIGHT_STAGES.getOrNull(stageIndex + 1) != value) malformed = true
                else stageIndex++
            }
            "kernel", "uid", "arch", "os", "version", "systemd", "disk" -> {
                val expectedStage = if (name == "version") "os" else name
                if (stage != expectedStage || fields.put(name, value) != null) malformed = true
                val valid = when (name) {
                    "uid" -> Regex("[0-9]{1,10}").matches(value)
                    "disk" -> value == "unknown" || (Regex("[0-9]{1,18}").matches(value) && value.toLongOrNull() != null)
                    "systemd" -> value in setOf("running", "degraded", "unavailable", "initializing", "starting", "maintenance", "stopping", "offline", "unknown")
                    else -> Regex("[A-Za-z0-9_.+-]{1,128}").matches(value)
                }
                if (!valid) malformed = true
            }
            "install" -> {
                if (stage != "installation" || installation != null || value !in setOf("present", "absent")) malformed = true
                else installation = value == "present"
            }
            "release" -> {
                if (stage != "installation" || installation == null || release != null || value !in setOf("3.10.0", "3.11.0", "unknown")) malformed = true
                else release = value
            }
            "remnant" -> {
                if (stage != "installation" || installation != false || !Regex("/[A-Za-z0-9_./@+:-]{1,500}").matches(value)) malformed = true
                else remnants += value
            }
            "warning" -> {
                val message = when {
                    value == "installation-remnants" && stage == "installation" -> "Обнаружены остаточные файлы 3x-UI."
                    value == "disk-unknown" && stage == "disk" -> "Свободное место определить не удалось (disk: unknown). Это информационное предупреждение, не запрет установки."
                    value == "systemd-degraded" && stage == "systemd" -> "systemd находится в состоянии degraded. Установка разрешена; проверьте неисправные службы отдельно."
                    value == "ports-unavailable:$stage" && stage in setOf("tcp", "udp") -> "Не удалось проверить локальные привязки ${stage.orEmpty().uppercase()}. Проверьте порт 80 и выбранный порт панели вручную."
                    value.startsWith("port:$stage/") && stage in setOf("tcp", "udp") -> {
                        val port = value.substringAfter('/').toIntOrNull()
                        if (port == null || port !in 1..65535 || (port != 80 && port != panelPort) || value != "port:$stage/$port") null
                        else "Занята локальная привязка ${stage.orEmpty().uppercase()}/$port. Установка не заблокирована; при необходимости освободите порт или выберите другой. Процессы не останавливались."
                    }
                    else -> null
                }
                if (message == null) malformed = true else warnings += message
            }
            "conflict" -> {
                val tool = value.removePrefix("tool:")
                val check = value.removePrefix("check:")
                when {
                    value.startsWith("tool:") && stage == "tools" && tool in PREFLIGHT_REQUIRED_TOOLS ->
                        conflicts += "Не найдена обязательная исполняемая команда $tool. Установите её и повторите проверку."
                    value.startsWith("check:") && check == stage && check in PREFLIGHT_STAGES ->
                        conflicts += "Не удалось выполнить обязательную проверку: ${preflightStageLabel(check)}. Исправьте окружение сервера и повторите проверку."
                    else -> malformed = true
                }
            }
            "complete" -> {
                if (value != "1" || stageIndex != PREFLIGHT_STAGES.lastIndex) malformed = true
                else complete = true
            }
            else -> malformed = true
        }
    }
    if (remnants.isNotEmpty()) warnings += "Обнаружены остаточные файлы и каталоги:\n" + remnants.joinToString("\n")
    val osId = fields["os"].orEmpty()
    val version = fields["version"].orEmpty()
    val major = version.substringBefore('.').toIntOrNull()
    val validVersion = Regex("""[0-9]{1,3}(?:\.[0-9]{1,3}){0,2}""").matches(version)
    val osSupported = validVersion && major != null && when (osId) {
        "debian" -> major >= 11
        "ubuntu" -> major >= 20
        else -> false
    }
    val architecture = fields["arch"].orEmpty()
    val linux = fields["kernel"] == "Linux"
    val root = fields["uid"] == "0"
    val systemd = fields["systemd"]
    val disk = fields["disk"]?.toLongOrNull()
    if (fields.containsKey("uid") && !root) conflicts += "Требуется SSH-сеанс с эффективным UID 0. Подключитесь как root; автоматический sudo не используется."
    if (fields.containsKey("kernel") && !linux) conflicts += "Поддерживается только Linux."
    if (fields.containsKey("os") && fields.containsKey("version") && !osSupported) conflicts += "Поддерживаются только Debian 11+ и Ubuntu 20+. Другие ОС, включая yum/dnf-системы, пока не поддерживаются."
    if (fields.containsKey("arch") && architecture !in setOf("x86_64", "aarch64")) conflicts += "Поддерживаются только архитектуры x86_64 и aarch64."
    if (systemd != null && systemd !in setOf("running", "degraded")) conflicts += "Требуется systemd как PID 1, каталог /run/systemd/system и состояние running или degraded. Проверьте запуск systemd."
    if (installation == true) warnings += "Обнаружена существующая установка 3x-ui/x-ui или её файлы. Ничего не запускалось и не удалялось; наличие установки само по себе не блокирует продолжение."
    val missingFields = fields.size != 7 || installation == null
    val checksComplete = complete && !malformed && result.exitCode == 0 && result.stderr.isBlank() && !missingFields
    val diagnostics = buildList {
        if (!complete) add("нет маркера завершения")
        if (result.exitCode != 0) add("ненулевой код завершения")
        if (malformed) add("некорректный протокол")
        if (missingFields) add("отсутствуют обязательные поля")
        if (result.stderr.isNotBlank()) add("получен stderr")
    }
    val stageLabel = preflightStageLabel(PREFLIGHT_STAGES.getOrNull(stageIndex))
    if (!checksComplete) {
        conflicts += "Проверка не подтверждена: ${diagnostics.joinToString("; ")}. Последний этап: $stageLabel. Повторите проверку после исправления окружения сервера. Установка запрещена."
    }
    val osName = when (osId) {
        "debian" -> "Debian"
        "ubuntu" -> "Ubuntu"
        else -> "Неподдерживаемая ОС"
    }
    val safeVersion = if (validVersion) version else "не определена"
    return ServerPreflight(
        os = "$osName $safeVersion",
        architecture = architecture.takeIf { it in setOf("x86_64", "aarch64") } ?: "не поддерживается",
        conflicts = conflicts.toList(),
        warnings = warnings.toList(),
        existingInstallation = installation == true,
        installationState = when {
            !checksComplete || !root || "conflict\tcheck:installation" in seen -> PanelInstallationState.UNKNOWN
            installation == true -> PanelInstallationState.PRESENT
            installation == false -> PanelInstallationState.ABSENT
            else -> PanelInstallationState.UNKNOWN
        },
        installedVersion = release?.takeIf { checksComplete && installation == true && it != "unknown" },
        checkedPanelPort = panelPort,
        root = root,
        rootChecked = fields.containsKey("uid"),
        platformChecked = fields.keys.containsAll(setOf("kernel", "os", "version", "arch")),
        supported = linux && osSupported && architecture in setOf("x86_64", "aarch64"),
        details = buildString {
            append("UID: ").append(if (!fields.containsKey("uid")) "не проверен" else if (root) "0" else "не 0")
            append("; systemd: ").append(when (systemd) {
                null -> "не проверен"
                "running" -> "running"
                "degraded" -> "degraded"
                else -> "не готов"
            })
            if (disk != null) append("; минимум свободно: ").append(disk / 1024).append(" МиБ")
            else append("; свободное место: неизвестно (disk: unknown)")
            append(if (checksComplete) ". Проверка завершена" else ". Проверка не подтверждена; этап: $stageLabel")
            if (!checksComplete) append("; ").append(diagnostics.joinToString("; "))
            append(". TCP/UDP-привязки порта 80")
            if (panelPort != null && panelPort != 80) append(" и порта панели ").append(panelPort)
            append(" проверяются только для предупреждений")
            if (needsHttpChallenge) append("; порт 80 потребуется для HTTP-проверки сертификата")
            append(". Внешняя доступность, DNS и правила межсетевого экрана не проверялись. Изменения не выполнялись.")
        },
    )
}

private val PREFLIGHT_STAGES = listOf("tools", "kernel", "uid", "arch", "os", "systemd", "disk", "installation", "tcp", "udp")
private val PREFLIGHT_REQUIRED_TOOLS = setOf("id", "uname", "awk", "systemctl", "apt-get", "bash", "sha256sum", "stat", "nohup", "setsid", "base64", "find")

private fun preflightStageLabel(stage: String?): String = when (stage) {
    "tools" -> "обязательные команды и выполнение bash"
    "kernel" -> "ядро Linux"
    "uid" -> "эффективный UID"
    "arch" -> "архитектура"
    "os" -> "данные ОС"
    "systemd" -> "готовность systemd"
    "disk" -> "информация о свободном месте"
    "installation" -> "наличие установки 3x-ui/x-ui"
    "tcp" -> "локальные TCP-привязки"
    "udp" -> "локальные UDP-привязки"
    else -> "запуск проверки"
}

private val PREFLIGHT_SCRIPT = """
    export LC_ALL PATH
    emit() { printf '%s\t%s\n' "${'$'}1" "${'$'}2"; }
    fail() { emit conflict "check:${'$'}1"; exit 1; }
    emit stage tools
    missing=0
    for tool in id uname awk systemctl apt-get bash sha256sum stat nohup setsid base64 find; do
        command -v "${'$'}tool" >/dev/null 2>&1 || { emit conflict "tool:${'$'}tool"; missing=1; }
    done
    [ "${'$'}missing" = 0 ] || exit 1
    executable=${'$'}(bash --noprofile --norc -c 'test -n "${'$'}BASH_VERSION" && printf EXECUTABLE') || fail tools
    [ "${'$'}executable" = EXECUTABLE ] || fail tools
    emit stage kernel
    kernel=${'$'}(uname -s) || fail kernel
    emit kernel "${'$'}kernel"
    emit stage uid
    uid=${'$'}(id -u) || fail uid
    emit uid "${'$'}uid"
    emit stage arch
    arch=${'$'}(uname -m) || fail arch
    emit arch "${'$'}arch"
    emit stage os
    [ -r /etc/os-release ] || fail os
    awk -F= '
        ${'$'}1 == "ID" || ${'$'}1 == "VERSION_ID" {
            v = substr(${'$'}0, index(${'$'}0, "=") + 1)
            gsub(/^[\047\042]|[\047\042]${'$'}/, "", v)
            if (${'$'}1 == "ID") { ids++; printf "os\t%s\n", v }
            else { versions++; printf "version\t%s\n", v }
        }
        END { if (ids != 1 || versions != 1) exit 1 }
    ' /etc/os-release || fail os
    emit stage systemd
    init=
    if [ -r /proc/1/comm ]; then read -r init < /proc/1/comm || fail systemd; fi
    state=unavailable
    if [ "${'$'}init" = systemd ] && [ -d /run/systemd/system ]; then
        state=${'$'}(systemctl is-system-running 2>/dev/null)
        status=${'$'}?
        case "${'$'}state:${'$'}status" in
            running:0|degraded:1) ;;
            initializing:*|starting:*|maintenance:*|stopping:*|offline:*|unknown:*) ;;
            *) fail systemd ;;
        esac
    fi
    emit systemd "${'$'}state"
    if [ "${'$'}state" = degraded ]; then emit warning systemd-degraded; fi
    emit stage disk
    minimum=unknown
    if command -v df >/dev/null 2>&1; then
        if free=${'$'}(df -Pk / /usr/local /etc /var 2>/dev/null); then
            minimum=${'$'}(printf '%s\n' "${'$'}free" | awk '
                NR > 1 {
                    if (${'$'}4 !~ /^[0-9]+${'$'}/) bad = 1
                    else if (++n == 1 || ${'$'}4 + 0 < min) min = ${'$'}4 + 0
                }
                END { if (bad || n != 4) exit 3; printf "%.0f", min }
            ')
            disk_status=${'$'}?
            case "${'$'}disk_status" in
                0) ;;
                3) minimum=unknown ;;
                *) fail disk ;;
            esac
        fi
    fi
    emit disk "${'$'}minimum"
    if [ "${'$'}minimum" = unknown ]; then emit warning disk-unknown; fi
    emit stage installation
    installed=absent
    remnants=0
    remnant_paths=
    remember_remnant() {
        remnants=1
        case "${'$'}1" in *[!A-Za-z0-9_./@+:-]*|'') return ;; esac
        case "
${'$'}remnant_paths
" in *"
${'$'}1
"*) return ;; esac
        remnant_paths="${'$'}{remnant_paths}${'$'}1
"
    }
    for alias in x-ui 3x-ui 3xui xui; do
        for path in \
            /usr/local/"${'$'}alias" /etc/"${'$'}alias" /var/lib/"${'$'}alias" /var/log/"${'$'}alias" \
            /usr/bin/"${'$'}alias" /usr/local/bin/"${'$'}alias" /usr/sbin/"${'$'}alias" /usr/local/sbin/"${'$'}alias" \
            /bin/"${'$'}alias" /sbin/"${'$'}alias" /opt/"${'$'}alias" \
            /root/"${'$'}alias" /root/."${'$'}alias" /root/"${'$'}alias".db \
            /etc/"${'$'}alias".db /var/lib/"${'$'}alias".db \
            /etc/init.d/"${'$'}alias" /etc/default/"${'$'}alias" /etc/sysconfig/"${'$'}alias"; do
            if [ -e "${'$'}path" ] || [ -L "${'$'}path" ]; then remember_remnant "${'$'}path"; fi
        done
        for tree in /usr/local/"${'$'}alias" /etc/"${'$'}alias" /var/lib/"${'$'}alias" /opt/"${'$'}alias" /root/"${'$'}alias" /root/."${'$'}alias"; do
            if [ -f "${'$'}tree/${'$'}alias" ] || [ -f "${'$'}tree/x-ui" ] || [ -f "${'$'}tree/${'$'}alias.db" ] || [ -f "${'$'}tree/x-ui.db" ]; then
                installed=present
            fi
            if [ "${'$'}installed" = absent ] && [ -d "${'$'}tree" ]; then
                evidence=${'$'}(find -H "${'$'}tree" -mindepth 1 \
                    \( -type d \( -name log -o -name logs -o -name cert -o -name certs -o -name certificates \
                        -o -name acme -o -name .acme.sh -o -name history -o -name jobs -o -name .xboard-install \
                        -o -name archive -o -name archives -o -name backup -o -name backups \) -prune \) -o \
                    \( -type f \( -name '*.db' -o -perm /111 \) \
                        ! -name '*.log' ! -name '*.pem' ! -name '*.crt' ! -name '*.key' \
                        ! -name '*.gz' ! -name '*.xz' ! -name '*.tgz' ! -name '*.zip' ! -name '*.tar' \
                        -print -quit \) 2>/dev/null) || fail installation
                if [ -n "${'$'}evidence" ]; then installed=present; fi
            fi
        done
        for path in \
            /usr/bin/"${'$'}alias" /usr/local/bin/"${'$'}alias" /usr/sbin/"${'$'}alias" /usr/local/sbin/"${'$'}alias" \
            /bin/"${'$'}alias" /sbin/"${'$'}alias"; do
            if [ -e "${'$'}path" ] || [ -L "${'$'}path" ]; then remnants=1; fi
        done
        for path in /root/"${'$'}alias".db /etc/"${'$'}alias".db /var/lib/"${'$'}alias".db /etc/init.d/"${'$'}alias"; do
            if [ -f "${'$'}path" ]; then installed=present; fi
        done
        for unitdir in /etc/systemd/system /lib/systemd/system /usr/lib/systemd/system /usr/local/lib/systemd/system \
            /run/systemd/system /run/systemd/transient /run/systemd/generator /run/systemd/generator.early /run/systemd/generator.late; do
            for path in \
                "${'$'}unitdir/${'$'}alias.service" "${'$'}unitdir/${'$'}alias@.service" "${'$'}unitdir/${'$'}alias@"*.service \
                "${'$'}unitdir/"*.wants/"${'$'}alias.service" "${'$'}unitdir/"*.requires/"${'$'}alias.service" \
                "${'$'}unitdir/"*.wants/"${'$'}alias@"*.service "${'$'}unitdir/"*.requires/"${'$'}alias@"*.service; do
                if [ -e "${'$'}path" ] || [ -L "${'$'}path" ]; then remember_remnant "${'$'}path"; fi
                if [ -f "${'$'}path" ] || { [ -L "${'$'}path" ] && [ -e "${'$'}path" ]; }; then installed=present; fi
            done
            for path in "${'$'}unitdir/${'$'}alias.service.d" "${'$'}unitdir/${'$'}alias@.service.d" "${'$'}unitdir/${'$'}alias@"*.service.d; do
                if [ -e "${'$'}path" ] || [ -L "${'$'}path" ]; then remember_remnant "${'$'}path"; fi
            done
        done
        service_state=${'$'}(systemctl show "${'$'}alias.service" "${'$'}alias@*.service" --property=LoadState --property=ActiveState 2>/dev/null)
        for property in ${'$'}service_state; do
            case "${'$'}property" in
                LoadState=loaded|LoadState=masked|LoadState=error|LoadState=bad-setting|\
                ActiveState=active|ActiveState=activating|ActiveState=reloading|ActiveState=deactivating) installed=present ;;
            esac
        done
    done
    for path in /root/.acme.sh /root/cert /root/.xboard-install /usr/local/x-ui-linux-*.tar.gz; do
        if [ -e "${'$'}path" ] || [ -L "${'$'}path" ]; then remember_remnant "${'$'}path"; fi
    done
    emit install "${'$'}installed"
    release=unknown
    release_binary=/usr/local/x-ui/x-ui
    if [ "${'$'}installed" = present ] && [ -d /usr/local/x-ui ] && [ ! -L /usr/local/x-ui ] && \
        [ -f "${'$'}release_binary" ] && [ ! -L "${'$'}release_binary" ] && [ -r "${'$'}release_binary" ]; then
        if release_checksum=${'$'}(sha256sum -- "${'$'}release_binary" 2>/dev/null); then
            case "${'$'}release_checksum" in
                "c79d4a9fe66838fc692a373a4e9b838d6858d9e7072aa57fe62fd2e0c52f8611  ${'$'}release_binary"|\
                "1c4f02618053c8b70309edcafea115d1f6f9bff1581f1474be9e5d008838c143  ${'$'}release_binary") release=3.10.0 ;;
                "fb7b3776334fce69299f90168c8c31377b69f89c7787088e6dbf561d07b3d345  ${'$'}release_binary"|\
                "053f4c0da86d6b6aff41af2aa7b579a62ebc3105ef5eac77fee5b79ab9a2a8a4  ${'$'}release_binary") release=3.11.0 ;;
            esac
        fi
    fi
    emit release "${'$'}release"
    if [ "${'$'}installed" = absent ] && [ "${'$'}remnants" = 1 ]; then
        printf '%s' "${'$'}remnant_paths" | awk 'NF && NR <= 128 { printf "remnant\t%s\n", ${'$'}0 }'
        if [ -z "${'$'}remnant_paths" ]; then emit warning installation-remnants; fi
    fi
    for protocol in tcp udp; do
        emit stage "${'$'}protocol"
        if ! command -v ss >/dev/null 2>&1; then
            emit warning "ports-unavailable:${'$'}protocol"
            continue
        fi
        if [ "${'$'}protocol" = tcp ]; then
            sockets=${'$'}(ss -H -ltn 2>/dev/null)
        else
            sockets=${'$'}(ss -H -lun 2>/dev/null)
        fi
        if [ "${'$'}?" != 0 ]; then
            emit warning "ports-unavailable:${'$'}protocol"
            continue
        fi
        bindings=${'$'}(printf '%s\n' "${'$'}sockets" | awk -v panel="${'$'}panel_port" -v proto="${'$'}protocol" '
            NF {
                if (NF < 5) { bad = 1; next }
                endpoint = ${'$'}4
                sub(/^.*:/, "", endpoint)
                if (endpoint !~ /^[0-9]+${'$'}/ || endpoint + 0 < 1 || endpoint + 0 > 65535) { bad = 1; next }
                port = endpoint + 0
                if (port == 80 || (panel != 0 && port == panel)) {
                    if (!seen[port]++) printf "warning\tport:%s/%d\n", proto, port
                }
            }
            END { if (bad) exit 3 }
        ')
        bindings_status=${'$'}?
        case "${'$'}bindings_status" in
            0) ;;
            3) emit warning "ports-unavailable:${'$'}protocol"; continue ;;
            *) fail "${'$'}protocol" ;;
        esac
        if [ -n "${'$'}bindings" ]; then printf '%s\n' "${'$'}bindings"; fi
    done
    emit complete 1
""".trimIndent() + "\n"
