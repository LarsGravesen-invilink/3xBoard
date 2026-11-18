package app.invilink.xboard.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.invilink.xboard.panels.PanelLocationResolver
import app.invilink.xboard.panels.countryFlagForClientName
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
internal fun InstructionsScreen(onBack: () -> Unit) {
    val palette = LocalXBoardPalette.current
    val focusManager = LocalFocusManager.current
    var query by rememberSaveable { mutableStateOf("") }
    var expandedTopics by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val terms = remember(query) { normalizeInstructionSearch(query).split(Regex("\\s+")).filter { it.isNotBlank() } }
    val visibleTopics = remember(terms) {
        instructionTopics.filter { topic -> terms.all { it in topic.searchText } }
    }
    ServerUiScreen(onBack = onBack) {
        ServerUiCaption("Помощь по 3xBoard")
        ServerUiHeading("Инструкции")
        ServerUiCaption("3xBoard работает с действующими панелями 3x-ui и позволяет установить 3x-ui и необходимые компоненты на VPS/VDS.")
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Поиск по инструкциям") },
            placeholder = { Text("Например: REALITY, порт, клиент") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            shape = RoundedCornerShape(18.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = palette.foreground,
                unfocusedBorderColor = palette.line,
                focusedLabelColor = palette.foreground,
                unfocusedLabelColor = palette.secondary,
                cursorColor = palette.foreground
            )
        )
        if (visibleTopics.isEmpty()) {
            ServerUiNotice("Ничего не найдено", "Измените поисковый запрос.")
        } else {
            visibleTopics.forEach { topic ->
                key(topic.id) {
                    InstructionTopicCard(
                        topic = topic,
                        expanded = topic.id in expandedTopics,
                        onToggle = {
                            expandedTopics = if (topic.id in expandedTopics) expandedTopics - topic.id
                            else expandedTopics + topic.id
                            focusManager.clearFocus()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun InstructionTopicCard(topic: InstructionTopic, expanded: Boolean, onToggle: () -> Unit) {
    val palette = LocalXBoardPalette.current
    ServerUiCard {
        BoardTextButton(
            onClick = onToggle,
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).semantics {
                stateDescription = if (expanded) "Тема раскрыта" else "Тема свёрнута"
            },
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = palette.foreground),
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(topic.category, style = MaterialTheme.typography.labelMedium, color = palette.secondary)
                Text(
                    topic.title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() }
                )
                Text(topic.summary, style = MaterialTheme.typography.bodyMedium, color = palette.secondary)
                Row(
                    Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(if (expanded) "Свернуть" else "Читать", style = MaterialTheme.typography.labelLarge)
                    Canvas(Modifier.size(16.dp)) {
                        val stroke = 1.6.dp.toPx()
                        drawLine(palette.foreground, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), stroke, StrokeCap.Round)
                        if (!expanded) drawLine(palette.foreground, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), stroke, StrokeCap.Round)
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(240)) + fadeIn(tween(200)),
            exit = shrinkVertically(tween(200)) + fadeOut(tween(150))
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                HorizontalDivider(color = palette.line)
                topic.blocks.forEach { (title, body) ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ServerUiTitle(title)
                        Text(body, style = MaterialTheme.typography.bodyLarge, color = palette.foreground)
                    }
                }
                if (topic.id == "client-emoji") ClientFlagCommand()
            }
        }
    }
}

@Composable
private fun ClientFlagCommand() {
    var input by rememberSaveable { mutableStateOf("") }
    var resolved by remember { mutableStateOf<Pair<String, String>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val resolver = remember { PanelLocationResolver() }
    val context = LocalContext.current
    LaunchedEffect(input) {
        resolved = null
        failed = false
        searching = false
        val value = input.trim()
        if (value.isEmpty()) return@LaunchedEffect
        val country = countryCodeForFlag(value)
        if (country != null) {
            resolved = value to country
            return@LaunchedEffect
        }
        val ipv4 = Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}").matches(value)
        val ipv6 = ':' in value && Regex("[0-9a-fA-F:]+" ).matches(value)
        if (!ipv4 && !ipv6) {
            failed = true
            return@LaunchedEffect
        }
        searching = true
        delay(500)
        val address = if (ipv6) "[$value]" else value
        val location = resolver.resolve("https://$address/")
        resolved = location?.countryCode?.let { value to it }
        failed = location == null
        searching = false
    }
    OutlinedTextField(
        value = input,
        onValueChange = { input = it.take(128) },
        label = { Text("Название страны или IP сервера") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        supportingText = { Text("Для IP адрес передаётся сервису ipwho.is; расположение приблизительное.") },
    )
    if (searching) Text("Определение страны…", style = MaterialTheme.typography.bodySmall)
    if (failed) Text("Страна не найдена. Укажите название, код страны или публичный IP.", style = MaterialTheme.typography.bodySmall)
    resolved?.takeIf { it.first == input.trim() }?.second?.let { code ->
        val command = ":$code:"
        val flag = countryFlagForClientName("$code:")
        OutlinedTextField(value = command, onValueChange = {}, readOnly = true,
            modifier = Modifier.fillMaxWidth(), label = { Text("Команда для вставки флага") },
            supportingText = { Text("Флаг: $flag") }, singleLine = true)
        BoardTextButton(onClick = {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("Команда флага", command))
        }) { Text("Копировать команду") }
    }
}

internal fun countryCodeForFlag(value: String): String? {
    val text = value.trim()
    val codes = Locale.getISOCountries()
    val upper = text.uppercase(Locale.ROOT)
    if (upper in codes) return upper
    return codes.firstOrNull { code ->
        val country = Locale("", code)
        listOf(country.getDisplayCountry(Locale("ru")), country.getDisplayCountry(Locale.ENGLISH),
            country.getDisplayCountry(Locale.getDefault())).any { it.equals(text, ignoreCase = true) }
    }
}

private fun normalizeInstructionSearch(text: String): String = text.lowercase(Locale.ROOT).replace('ё', 'е')

private data class InstructionTopic(
    val id: String,
    val category: String,
    val title: String,
    val summary: String,
    val blocks: List<Pair<String, String>>,
    val searchTerms: String = "",
) {
    val searchText = normalizeInstructionSearch(
        listOf(category, title, summary, searchTerms).plus(blocks.flatMap { listOf(it.first, it.second) }).joinToString(" ")
    )
}

private fun instructionTopic(
    id: String,
    category: String,
    title: String,
    summary: String,
    vararg blocks: Pair<String, String>,
    searchTerms: String = "",
) = InstructionTopic(id, category, title, summary, blocks.toList(), searchTerms)

private data class ProtocolGuideEntry(
    val id: String,
    val title: String,
    val summary: String,
    val suitableFor: String,
    val dependencies: String,
    val parameters: String,
    val limitations: String,
)

@Composable
private fun ProtocolGuide() {
    var expanded by rememberSaveable { mutableStateOf(emptyList<String>()) }
    Text(
        "Выберите протокол по типу сети и требуемой совместимости. Раскрывайте только нужный раздел: в нём указаны назначение, обязательные зависимости и параметры, которые должны совпадать у клиента и сервера.",
        style = MaterialTheme.typography.bodyLarge,
    )
    ProtocolGuideNotice("Важно", "Маскировка затрудняет классификацию трафика, но не гарантирует обход ограничений. Открытый TCP/UDP-порт, firewall, версия Xray/клиента и параметры обеих сторон остаются обязательными условиями работы.")
    protocolGuideEntries.forEach { entry ->
        val open = entry.id in expanded
        ServerUiCard {
            BoardTextButton(
                onClick = { expanded = if (open) expanded - entry.id else expanded + entry.id },
                modifier = Modifier.fillMaxWidth().semantics { stateDescription = if (open) "Раздел раскрыт" else "Раздел свёрнут" },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = LocalXBoardPalette.current.foreground),
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 6.dp),
            ) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(entry.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    Text(entry.summary, style = MaterialTheme.typography.bodyMedium, color = LocalXBoardPalette.current.secondary)
                    Text(if (open) "Свернуть параметры" else "Открыть параметры", style = MaterialTheme.typography.labelLarge)
                }
            }
            AnimatedVisibility(open, enter = expandVertically(tween(200)) + fadeIn(tween(160)), exit = shrinkVertically(tween(160)) + fadeOut(tween(120))) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HorizontalDivider(color = LocalXBoardPalette.current.line)
                    ProtocolGuideSection("Для чего подходит", entry.suitableFor)
                    ProtocolGuideSection("Зависимости", entry.dependencies)
                    ProtocolGuideSection("Параметры и согласование", entry.parameters)
                    ProtocolGuideSection("Ограничения и совместимость", entry.limitations)
                }
            }
        }
    }
    ProtocolGuideGlossary()
}

@Composable
private fun ProtocolGuideSection(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ServerUiTitle(title)
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ProtocolGuideNotice(title: String, body: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ServerUiTitle(title)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = LocalXBoardPalette.current.secondary)
    }
}

@Composable
private fun ProtocolGuideGlossary() {
    var open by rememberSaveable { mutableStateOf(false) }
    ServerUiCard {
        BoardTextButton(onClick = { open = !open }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = LocalXBoardPalette.current.foreground), contentPadding = PaddingValues(0.dp)) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Общие термины", style = MaterialTheme.typography.titleMedium)
                Text(if (open) "Свернуть" else "SNI, TLS, uTLS, ALPN, CDN, DPI, TCP/UDP и каскады", style = MaterialTheme.typography.bodyMedium, color = LocalXBoardPalette.current.secondary)
            }
        }
        AnimatedVisibility(open, enter = expandVertically(tween(200)) + fadeIn(tween(160)), exit = shrinkVertically(tween(160)) + fadeOut(tween(120))) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HorizontalDivider(color = LocalXBoardPalette.current.line)
                protocolGlossary.forEach { (title, body) -> ProtocolGuideSection(title, body) }
            }
        }
    }
}

private val protocolGuideEntries = listOf(
    ProtocolGuideEntry(
        "vless", "VLESS", "Гибкий современный протокол Xray с UUID-аутентификацией; защиту и транспорт выбирают отдельно.",
        "Повседневный VPN, видео, мессенджеры, сети с ограничениями, CDN/reverse proxy и каскадные схемы.",
        "Сам VLESS не даёт классического шифрования: для защищённого подключения нужен TLS или REALITY. Vision применяется только с совместимыми VLESS/TCP/RAW конфигурациями TLS/REALITY.",
        "UUID — учётные данные клиента. TCP/RAW — минимальные накладные расходы; WS — совместимый CDN/reverse proxy; gRPC — HTTP/2; XHTTP — транспорт Xray; HTTPUpgrade похож на WS. Для TLS согласуйте сертификат, SNI, ALPN и при необходимости uTLS fingerprint. Для REALITY согласуйте target, serverNames, publicKey и shortId; приватный ключ остаётся на сервере.",
        "Путь/Host транспорта должны совпасть с клиентом и proxy. Vision нельзя использовать с XHTTP. REALITY не делает соединение необнаружимым и зависит от версии Xray/клиента."
    ),
    ProtocolGuideEntry(
        "amneziawg", "AmneziaWG", "Модифицированный WireGuard: полноценный UDP-туннель с обфускацией характерных признаков WireGuard.",
        "Игры, мобильные сети, видео и другие сценарии с низкой задержкой; туннель для устройства или локальной сети.",
        "Работает только поверх UDP. Нужны ключи WireGuard, peer-параметры и согласованные настройки маскировки. TLS, SNI, uTLS и ALPN для AmneziaWG не используются.",
        "Клиенту и серверу нужны private/public key, allowed IPs, endpoint и при необходимости pre-shared key/keepalive. Jc/Jmin/Jmax задают junk packets и размеры; S1–S4, H1–H4 и I1–I5 меняют признаки пакетов. MTU и DNS влияют на работу туннеля.",
        "При блокировке UDP соединение не работает. Неверные маскировочные параметры или allowed IPs не позволят установить peer-соединение. Нужна поддержка AmneziaWG на сервере и в клиенте."
    ),
    ProtocolGuideEntry(
        "shadowsocks", "Shadowsocks", "Лёгкий зашифрованный прокси с общим паролем и выбранным шифром.",
        "Повседневный прокси, сайты, видео, мессенджеры, нестабильные сети и отдельный узел каскада.",
        "Клиент и сервер должны использовать одинаковые пароль и method. Для SS2022 ключ должен соответствовать выбранному алгоритму.",
        "Современный выбор — AEAD: AES-128-GCM, AES-256-GCM или ChaCha20-Poly1305; в приложении доступны и варианты SS2022. TCP подходит для обычных соединений, UDP — для DNS, звонков и игр. SNI/uTLS появляются только у внешних плагинов, не у базового Shadowsocks.",
        "Не является полноценным системным VPN без режима VPN в клиентском приложении. SIP003-плагины и их совместимость на стороне сервера/клиента настраиваются отдельно."
    ),
    ProtocolGuideEntry(
        "vmess", "VMess", "Протокол семейства V2Ray с UUID-аутентификацией и широкой совместимостью со старой инфраструктурой.",
        "Существующие VMess-конфигурации, универсальный прокси и подключение через разные транспорты.",
        "Нужен UUID; время клиента и сервера должно быть синхронизировано. TLS — отдельный слой защиты и требует сертификат/SNI при его использовании.",
        "Доступны TCP/RAW, WS, gRPC, HTTPUpgrade и XHTTP при поддержке ядром. Для TLS согласуйте SNI, ALPN и при необходимости uTLS fingerprint. Шифрование и транспорт должны поддерживаться конкретным клиентом.",
        "VMess — более старый и сложный вариант, чем VLESS. Не все клиенты и версии Xray одинаково поддерживают новые транспорты."
    ),
    ProtocolGuideEntry(
        "trojan", "Trojan", "TLS-ориентированный прокси с парольной аутентификацией, внешне похожий на обычное защищённое веб-соединение.",
        "HTTPS-инфраструктура, reverse proxy/CDN при совместимой схеме и альтернативный прокси-узел.",
        "Нужен действительный TLS-сертификат, доступный серверу, и пароль клиента. SNI обычно соответствует имени из сертификата.",
        "TCP — базовый вариант; WS, gRPC и HTTPUpgrade возможны при поддержке схемой и ядром. TLS, SNI, ALPN и uTLS fingerprint должны быть согласованы с клиентом, сертификатом и reverse proxy.",
        "Без действительного сертификата TLS-схема не будет корректной. CDN и reverse proxy поддерживают не все транспорты и требуют совпадения пути/Host/маршрутизации."
    ),
    ProtocolGuideEntry(
        "hysteria2", "Hysteria2", "Высокопроизводительный прокси на QUIC/UDP, рассчитанный на сети с потерями пакетов и мобильные подключения.",
        "Высокое разрешение видео, большие загрузки, мобильные и нестабильные сети, дополнительный узел VPN-инфраструктуры.",
        "QUIC поверх UDP обязателен: нужен открытый UDP-порт. Нужны TLS-сертификат/ключ на сервере, auth клиента и совместимый Hysteria2-клиент. При Salamander общий пароль должен совпадать на обеих сторонах.",
        "SNI выбирает TLS-сервер; ALPN по умолчанию h3; TLS в QUIC обеспечивает шифрование. UDP Mask Salamander использует password; Gecko — вариант с packetSize. Masquerade задаёт ответ для невалидных обращений. QUIC Params и uTLS доступны только при поддержке конкретной реализации.",
        "Не использует TCP, WS или gRPC. При полной блокировке UDP не подключится. Маскировка не заменяет TLS и не гарантирует обход DPI."
    ),
)

private val protocolGlossary = listOf(
    "SNI" to "Имя сервера в TLS-рукопожатии: помогает выбрать виртуальный сервер и сертификат. Само по себе не шифрует трафик и может отличаться от IP подключения.",
    "TLS" to "Криптографическая защита соединения: шифрование, целостность и аутентификация сервера. TLS не является отдельным VPN-протоколом.",
    "uTLS / fingerprint" to "Имитация характеристик TLS-рукопожатия браузера. Не заменяет TLS и работает только там, где это поддерживают протокол и клиент.",
    "ALPN" to "Согласование прикладного протокола внутри TLS: http/1.1, h2 (HTTP/2), h3 (HTTP/3). Значение должно соответствовать используемой схеме.",
    "CDN и reverse proxy" to "Промежуточная веб-инфраструктура. Совместимость зависит от конкретного транспорта, домена, TLS, пути и Host; поддержка не универсальна.",
    "DPI" to "Глубокий анализ характеристик трафика. Обфускация меняет признаки соединения, но не обещает невозможность обнаружения или блокировки.",
    "TCP и UDP" to "TCP контролирует доставку и порядок данных. UDP передаёт датаграммы с меньшими накладными расходами, но без гарантии доставки; QUIC и AmneziaWG используют UDP.",
    "Каскад" to "Маршрут клиент → входной сервер → выходной сервер → интернет. Межсерверный outbound должен быть совместим с inbound выхода; гибридные схемы требуют отдельной настройки обоих участков.",
)

private val protocolGuideSearchTerms = (protocolGuideEntries.joinToString(" ") { entry ->
    listOf(entry.title, entry.summary, entry.suitableFor, entry.dependencies, entry.parameters, entry.limitations).joinToString(" ")
} + " " + protocolGlossary.joinToString(" ") { it.first + " " + it.second })

private val instructionTopics = listOf(
    instructionTopic(
        "protocols", "Начало работы", "Выбор протокола", "Выберите профиль в мастере — обязательные параметры уже заполнены. Значения по умолчанию можно оставить без замены; меняйте их только при необходимости или если хотите другой профиль.",
        "VLESS + REALITY" to "Для TCP или XHTTP. Мастер создаёт ключи REALITY, short ID и путь. На клиенте должны совпадать public key, short ID, SNI и выбранный транспорт.",
        "VMess и Shadowsocks" to "Подходят для совместимых клиентов. Для VMess создаётся TCP-инбаунд. Для Shadowsocks мастер генерирует серверный ключ под выбранный шифр.",
        "Trojan" to "Использует TCP с TLS. Проверьте пути к certificate и key на сервере; по умолчанию подставляются пути сертификата панели.",
        "Hysteria2 и AmneziaWG" to "Работают по UDP. Для Hysteria2 нужны корректные TLS certificate/key, SNI и открытый UDP-порт. Для AmneziaWG мастер создаёт ключи и свободную внутреннюю подсеть; MTU и DNS можно изменить.",
        "Общее правило" to "Порт, протокол и параметры клиента должны совпадать с инбаундом. API-доступность панели не подтверждает доступность VPN-порта извне."
    ),
    instructionTopic(
        "panel-add", "Инструкции", "Добавление панели по API-токену", "Подключение к уже работающей 3x-ui по HTTP или HTTPS.",
        "1. Выберите Token API" to "Нажмите + в шапке приложения и выберите значок Token API. Название панели можно указать вручную или оставить пустым.",
        "2. Укажите адрес и порт" to "Введите IP или домен, либо полный адрес с путём панели, например http://example.com/secret/. Порт управления укажите отдельно; это не обязательно порт SSH или VPN. Без схемы используется HTTPS.",
        "3. Добавьте токен" to "Вставьте API-токен из 3x-ui без префикса Bearer. Пароль администратора вместо токена не подходит. Для HTTPS необходим действующий сертификат.",
        "Требования к версии" to "Рекомендуется 3x-ui версии 3.10 или новее. Подключение к версии ниже 3.10 возможно, однако корректная работа такой панели не гарантируется.",
        "4. Сохраните и проверьте" to "Сохраните подключение и дождитесь ответа панели. HTTP не шифрует токен и данные; используйте HTTPS либо доверенное защищённое соединение. При ошибке проверьте адрес, путь, порт и права токена."
    ),
    instructionTopic(
        "panel-add-ssh", "Инструкции", "Добавление существующей панели по SSH", "Автоматическое обнаружение работающей 3x-ui на сервере.",
        "1. Выберите SSH" to "Нажмите + в шапке приложения и выберите значок SSH. Укажите SSH-адрес, порт и пользователя с root-доступом; выберите пароль или закрытый ключ.",
        "2. Подключитесь" to "Нажмите «Подключиться и добавить». 3xBoard обнаруживает работающую штатную службу 3x-ui с SQLite, определяет адрес и путь панели и получает или создаёт API-токен.",
        "Требования к версии" to "Рекомендуется штатная native-установка 3x-ui версии 3.10 или новее. Подключение к версии ниже 3.10 возможно, однако корректная работа такой панели не гарантируется.",
        "3. Проверьте подключение" to "Панель должна быть доступна с устройства по обнаруженному адресу и порту. При HTTPS нужен действующий сертификат. Данные панели не сбрасываются; импортированную запись можно удалить из 3xBoard, но удаление 3x-ui с VPS для неё недоступно."
    ),
    instructionTopic(
        "clean-server-ssh", "Инструкции", "Чистый сервер по SSH", "Добавление SSH-сервера без панели и установка 3x-ui через приложение.",
        "1. Добавьте SSH" to "Нажмите + → SSH, укажите адрес, порт, root-пользователя и пароль или приватный ключ. После проверки 3xBoard предложит добавить сервер без панели.",
        "2. Запустите установку" to "На сообщении об отсутствии 3x-ui выберите «Установить сейчас», затем выполните шаги из раздела «Установка 3x-ui на VPS»: параметры панели, SSL и подтверждение установки.",
        "3. После установки" to "Дождитесь завершения. Панель и SSH-сервер будут добавлены в 3xBoard автоматически. Перед началом откройте нужные порты в firewall ОС и у провайдера."
    ),
    instructionTopic(
        "install", "Инструкции", "Установка 3x-ui на VPS", "Установка 3x-ui и компонентов на удалённый VPS/VDS через SSH.",
        "1. Подключитесь к VPS/VDS" to "Откройте «Установка 3x-ui» в меню шестерёнки: нужны Debian 11+ / Ubuntu 20+, systemd и x64 / ARM64.\nУкажите SSH-адрес, порт, пользователя root и пароль; при необходимости выберите приватный ключ.\nПодключитесь и дождитесь проверки готовности.",
        "2. Задайте параметры панели" to "Выберите «Параметры новой установки», заполните название, логин и пароль администратора.\nУкажите свободный порт панели и секретный путь без слешей либо используйте генераторы.",
        "3. Выберите SSL" to "«IP сервера» — автоматическая настройка SSL для определённого публичного IPv4.\n«Домен» — сертификат для вашего домена; его DNS должен указывать на VPS.\nОткройте порт 80 для выпуска сертификата и порт панели в firewall ОС и у провайдера.\nДругие службы сами по себе не блокируют установку; занятые порты дают предупреждения без блокировки, но конфликты нужно устранить.",
        "4. Подтвердите установку" to "Нажмите «Продолжить», подтвердите установку и дождитесь результата: процент обозначает этапы.\nПосле успешной новой установки панель добавится в 3xBoard, а SSH-запись — в «Серверы с SSH».\nПри обрыве SSH сначала проверьте состояние задачи: она могла продолжиться на VPS.",
        "Источник 3x-ui" to "Устанавливаются официальные release-версии проекта MHSanaei из репозитория: https://github.com/MHSanaei/3x-ui. 3xBoard проверяет полученный выпуск перед установкой.",
        "5. Если 3x-ui уже установлена" to "Обновление и переустановка с сохранением данных доступны только для штатной официальной native-установки 3.10.0/3.11.0.\nОбслуживание не добавляет существующую панель в 3xBoard автоматически."
    ),
    instructionTopic(
        "client-emoji", "Инструкции", "Имя клиента и эмодзи", "Добавление флагов стран в имя клиента.",
        "Как вставить флаг" to "Введите название страны, её двухбуквенный код или публичный IP сервера. Скопируйте появившуюся команду и вставьте её в любое место имени клиента: она автоматически превратится во флаг. Можно добавить несколько флагов.",
        "Пример" to "Команда :DE: вставляет флаг Германии. В начале имени можно использовать DE:. При удалении флаг удаляется целиком."
    ),
    instructionTopic(
        "cascade", "Инструкции", "Каскадное подключение", "Маршрут клиент → входной узел → выходной узел → интернет.",
        "1. Подготовьте панели" to "Добавьте две разные панели. Для Hysteria2 и AmneziaWG откройте нужные UDP-порты в firewall ОС и у провайдера.",
        "2. Выберите профиль" to "В «Администрирование» → «Мастер каскада» выберите VLESS + REALITY, Hysteria2 или AmneziaWG, затем входной и выходной узлы. Рабочие параметры подставляются автоматически: их можно оставить без замены или изменить при необходимости либо по своему выбору.",
        "3. Проверьте параметры" to "Укажите свободные порты и адрес выходного сервера. Для VLESS согласуйте транспорт, SNI и fingerprint; для Hysteria2 — SNI, certificate/key и ALPN; для AmneziaWG — MTU и DNS. Разрешите перезапуск Xray.",
        "4. Создайте и управляйте" to "Дождитесь завершения. В «Управление каскадами» карточки входного и выходного узлов открывают связанные инбаунды и клиентов. Служебные параметры каскада изменять вручную нельзя.",
        "5. Проверьте VPN" to "Создайте обычного клиента на входном инбаунде, импортируйте его конфигурацию в совместимое VPN-приложение и проверьте подключение. Для UDP-профилей межузловой TCP ping не применяется."
    ),
    instructionTopic(
        "routing-rules", "Инструкции", "Правила маршрутизации", "Настройка правил Xray, доменов, IP и направлений трафика.",
        "Открытие и порядок" to "Откройте «Серверы с SSH» → управление нужным сервером → «Маршрутизация». Правила загружаются из панели и проверяются сверху вниз: применяется первое совпавшее. Если условий несколько, они должны выполняться одновременно. Удерживайте свёрнутое правило, чтобы изменить порядок.",
        "Тип и inboundTag" to "Type обычно равен field; «По умолчанию» не задаёт поле явно. InboundTag ограничивает правило выбранными входящими подключениями. Можно выбрать несколько тегов или добавить свой; пустой список не ограничивает входящий тег.",
        "Домены" to "В готовых условиях выберите вариант и допишите значение после двоеточия: domain:example.com — домен и поддомены; full:example.com — точное имя; keyword:слово — часть имени; regexp:выражение — регулярное выражение; geosite:категория — список из базы. Каждое значение вводится с новой строки. category-ads-all доступна только при наличии такой категории на сервере.",
        "IP и сеть" to "В поле IP указывайте адреса, CIDR или категории geoip: по одному в строке. geoip:private — частные адреса. Наличие конкретной страны или категории зависит от установленной базы. Port — порт назначения; Source port и Source IP относятся к стороне клиента. Допустимы отдельные порты и диапазоны, например 80,443,1000-2000.",
        "Протокол и пользователь" to "Network выбирает tcp, udp или оба. Protocol задаёт распознанный протокол: http, tls, quic или bittorrent; для распознавания может понадобиться sniffing на инбаунде. User — имена клиентов, по одному в строке. Attrs — JSON-объект атрибутов HTTP, например {\":method\":\"GET\"}; возможности зависят от протокола и sniffing.",
        "Действие" to "OutboundTag направляет совпавший трафик в указанный исходящий шлюз; для блокировки выберите имеющийся outbound типа blackhole. BalancerTag выбирает балансировщик вместо outboundTag. Не задавайте оба одновременно. Свой тег должен существовать в конфигурации Xray.",
        "Общие настройки" to "Domain strategy: AsIs не инициирует DNS-разрешение для маршрутизации; IPIfNonMatch повторно проверяет правила с DNS, если сначала совпадений не было; IPOnDemand разрешает домен при первом необходимом IP-условии. Domain matcher — вариант алгоритма поиска доменов; поддержка linear и mph зависит от версии Xray. «По умолчанию» удаляет явное значение, оставляя выбор ядру.",
        "Сохранение" to "Сохраните каждое открытое правило, затем нажмите «Сохранить и обновить». Приложение проверит настройки панели, отправит изменения и запросит перезапуск. Изменения маршрутизации могут прервать подключения. Если подтверждение не получено, обновите список с панели перед повтором."
    ),
    instructionTopic(
        "inbound-create", "Инструкции", "Быстрое создание инбаунда", "Мастер создаёт инбаунд с готовыми параметрами и оставляет их редактируемыми до применения.",
        "1. Откройте мастер" to "На главном экране нажмите «Мастер инбаундов», выберите протокол и панель. Доступны VLESS + REALITY, VMess, Trojan, Shadowsocks, Hysteria2 и AmneziaWG.",
        "2. Проверьте профиль" to "Мастер подбирает незанятый порт среди инбаундов панели и, при сохранённом SSH, проверяет его на сервере. Порт можно изменить. Для UDP-профилей предлагается UDP-порт; для VLESS можно выбрать TCP, XHTTP или оба.",
        "3. Измените нужное" to "Предзаполненные значения можно оставить без замены; меняйте их при необходимости или если хотите иной профиль. VLESS: транспорт, SNI и fingerprint. Hysteria2: SNI, certificate/key и ALPN. AmneziaWG: MTU и DNS. Trojan: certificate/key. Shadowsocks: шифр. VMess использует TCP-профиль по умолчанию.",
        "4. Создайте клиента" to "Введите имя инбаунда, создайте его, затем добавьте клиента в карточке инбаунда. Перед импортом проверьте, что инбаунд и клиент включены, а порт открыт в firewall и у провайдера."
    ),
    instructionTopic(
        "client-create", "Инструкции", "Добавление клиента", "Клиент — учётная запись внутри выбранного инбаунда.",
        "1. Откройте инбаунд" to "Нажмите + в карточке инбаунда, задайте имя и включите клиента. Команда :DE: или префикс DE: добавляет флаг страны в имя.",
        "2. Проверьте данные" to "VLESS и VMess используют UUID; Trojan и Shadowsocks — пароль; Hysteria2 — auth; AmneziaWG — ключи peer. Генератор приложения подставляет обязательные значения для выбранного протокола.",
        "3. Ограничения и импорт" to "0 для трафика и срока означает отсутствие ограничения. После сохранения откройте клиента, скопируйте ссылку или QR-код и импортируйте конфигурацию в совместимое приложение."
    ),
    instructionTopic(
        "check", "Инструкции", "Проверка работы", "Доступность управления и работа VPN проверяются отдельно.",
        "1. Проверьте панель и инбаунд" to "Обновите данные: «Доступна» означает ответ API панели, а не работающий VPN.\nПроверьте включение клиента и инбаунда, лимиты и срок.\nИнбаунд должен слушать нужный адрес и порт; проверьте доступность этого порта извне и правила TCP/UDP.",
        "2. Импортируйте подключение" to "Откройте клиента → «Подключение»: скопируйте ссылку или отсканируйте QR в Incy, Happ либо другом совместимом VPN-клиенте.\n«Открыть в VPN-клиенте» доступно для поддерживаемых ссылок и установленных приложений.\n«Подписка» загружает список подключений и доступна, только если настроена в панели.",
        "3. Проверьте VPN" to "Подключитесь в VPN-клиенте и проверьте загрузку страницы через туннель.\nЕсли соединения нет, сверьте UUID/пароль, транспорт, SNI, TLS или ключ и shortId REALITY с настройками инбаунда."
    ),
    instructionTopic(
        "webui-protection", "Безопасность", "Защита WebUI панелей", "Временное закрытие доступа к панели через боковое меню.",
        "1. Включите меню" to "В настройках 3xBoard откройте «Меню защиты WebUI панелей», включите его и при необходимости выберите панели для исключения. Защита доступна для панелей с сохранёнными SSH-данными и root-доступом.",
        "2. Закройте доступ" to "Свайпните от левого края и откройте меню вторым свайпом. Нажатие WebUI закрывает порт доступных выбранных панелей; вместе с браузером становится недоступен и API на этом порту. Недоступные панели пропускаются.",
        "3. Восстановите доступ" to "Повторное нажатие WebUI удаляет правила, созданные приложением. При ошибке проверьте состояние серверов и повторите восстановление. Правила могут не пережить перезагрузку сервера."
    ),
    instructionTopic(
        "panel-status", "Интерфейс", "Страна, доступность и задержка", "Как читать расположение и состояние панели.",
        "1. Расположение" to "Страна, город и флаг панели определяются приблизительно по публичному IP сервера.\n«Локация не определена» — данные о расположении отсутствуют.",
        "2. Состояние API" to "«Доступна» — API ответил; «Ошибка авторизации» — проверьте токен и его права.\n«Недоступна» — проверьте HTTP или HTTPS, DNS, порт и службу панели.\nAPI в мс — время запроса к панели, не скорость и не задержка VPN."
    ),
    instructionTopic(
        "metrics", "Интерфейс", "Счётчики, трафик и CPU", "Показатели, полученные от панели, а не измеренные телефоном.",
        "1. Количество и нагрузка" to "Значки инбаундов и пользователей показывают число инбаундов и учётных записей клиентов панели.\nЭто не число активных VPN-соединений; CPU — сообщённая панелью загрузка процессора сервера в процентах.",
        "2. Трафик" to "↓ «Получено» и ↑ «Отправлено» — накопленные счётчики панели; на главной они суммируются по инбаундам.\nВнутри инбаунда и клиента показаны их расход и лимит, а не скорость передачи.\n«—» означает, что значение не получено, а не нулевое потребление."
    ),
    instructionTopic(
        "state", "Интерфейс", "Сроки, офлайн и SSH", "Как отличить сохранённые данные от данных панели.",
        "1. Сроки и лимиты" to "«Срок» в карточке — дата окончания доступа; в редакторе используется Unix-время в миллисекундах.\n0 — бессрочно; у клиента отрицательное число задаёт длительность после первого подключения.\nТрафик вводится в ГиБ; для трафика, IP и HWID 0 означает без лимита, применение IP/HWID зависит от панели.",
        "2. Offline" to "Offline означает, что приложению не удалось подтвердить доступ к интернету. Сохранённые данные могут быть устаревшими. Изменения требуют доступной панели; после обрыва связи обновите данные и проверьте результат перед повтором.",
        "3. Метка SSH" to "SSH на карточке означает наличие связанной записи удалённого управления, а не активное SSH-соединение.\nШестерёнка рядом открывает действия сервера; установка и SSH-команды требуют сети."
    ),
    instructionTopic(
        "navigation-home", "Навигация", "Шапка и страницы панелей", "Карта главного экрана.",
        "1. Шапка" to "Шестерёнка — меню; значок темы — светлая/тёмная тема; замок — блокировка 3xBoard.\n+ в общей шапке — подключение действующей панели. Нажатие на уведомление скрывает его.",
        "2. Список панелей" to "На странице размещается до четырёх карточек; другие страницы доступны горизонтальным свайпом.\nКарточка открывает инбаунды и клиентов, потягивание списка вниз обновляет показатели."
    ),
    instructionTopic(
        "navigation-panel", "Навигация", "Панель → инбаунды → клиенты", "Действия внутри выбранной панели.",
        "1. Инбаунды" to "+ над списком — создание инбаунда; + в его карточке — добавление клиента.\n«Показать клиентов» раскрывает список; карандаш открывает настройки, корзина — удаление.",
        "2. Клиенты" to "Строка клиента открывает «Подключение» и «Подписка» со ссылками и QR.\nКарандаш у клиента — редактирование; «Общий клиент · инбаундов» показывает область общей записи.\nУдаление инбаунда отключает подключения через него."
    ),
    instructionTopic(
        "navigation-menu", "Навигация", "Меню и действия сервера", "Разделы меню шестерёнки и удалённое управление.",
        "1. Разделы меню" to "«Установка 3x-ui» — мастер SSH; «Настройки 3xBoard» — параметры приложения.\n«Серверы с SSH» — серверы, добавленные по SSH или установленные через 3xBoard.\n«О приложении» — значок i в шапке меню; там указаны версия и сведения о разработке.",
        "2. Администрирование" to "Откройте «Администрирование» в боковом меню и выберите сервер, либо нажмите шестерёнку SSH-карточки. Здесь доступны каскады, маршрутизация и данные 3x-ui, а также действия с сервером.\n«Перезапустить сервер» — перезагрузка ОС; «Перезагрузить xRay» — перезапуск VPN-службы.",
        "3. Удаление" to "«Удалить сервер из 3xBoard» убирает локальную запись, не останавливая 3x-ui на VPS.\nДля установки через 3xBoard доступно «Удалить и очистить VPS/VDS»: это необратимо удаляет 3x-ui и её базу данных без резервной копии. Для импортированных серверов доступно только локальное удаление.\nЭто не очистка всей ОС: другие службы и общие сертификаты сохраняются."
    )
).sortedBy { topic ->
    listOf("protocols", "install", "clean-server-ssh", "panel-add", "panel-add-ssh", "inbound-create", "client-create", "client-emoji", "cascade", "routing-rules", "check", "webui-protection", "panel-status", "metrics", "state", "navigation-home", "navigation-panel", "navigation-menu")
        .indexOf(topic.id).takeIf { it >= 0 } ?: Int.MAX_VALUE
}
