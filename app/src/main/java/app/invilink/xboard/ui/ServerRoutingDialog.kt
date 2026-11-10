package app.invilink.xboard.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import app.invilink.xboard.panels.PanelApi
import app.invilink.xboard.panels.XrayRoutingSnapshot
import app.invilink.xboard.panels.PanelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private data class RoutingRuleDraft(
    val id: String = UUID.randomUUID().toString(),
    val raw: String,
    val domains: String,
    val ips: String,
    val outbound: String,
    val domainsChanged: Boolean = false,
    val ipsChanged: Boolean = false,
    val outboundChanged: Boolean = false,
    val extra: Map<String, String> = emptyMap(),
    val isNew: Boolean = false,
) {
    fun field(key: String): String = extra[key] ?: run {
        when (val value = JSONObject(raw).opt(key)) {
            is JSONArray -> (0 until value.length()).map { value.optString(it) }.joinToString("\n")
            null, JSONObject.NULL -> ""
            else -> value.toString()
        }
    }
    fun withField(key: String, value: String) = copy(extra = extra + (key to value))

    fun encoded(): String {
        val rule = JSONObject(raw)
        fun writeList(key: String, text: String) {
            val values = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (values.isEmpty()) rule.remove(key) else rule.put(key, JSONArray(values))
        }
        if (domainsChanged) writeList("domain", domains)
        if (ipsChanged) writeList("ip", ips)
        if (outboundChanged) {
            if (outbound.isBlank()) rule.remove("outboundTag") else rule.put("outboundTag", outbound.trim())
        }
        for ((key, value) in extra) {
            when {
                value.isBlank() -> rule.remove(key)
                key in setOf("inboundTag", "protocol", "user", "sourceIP", "source") -> writeList(key, value)
                key == "attrs" -> rule.put(key, JSONObject(value))
                else -> rule.put(key, value.trim())
            }
        }
        return rule.toString()
    }
}

private fun routingRules(snapshot: XrayRoutingSnapshot): List<RoutingRuleDraft> {
    val rows = JSONObject(snapshot.template).getJSONObject("routing").getJSONArray("rules")
    return (0 until rows.length()).map { i ->
        val rule = rows.getJSONObject(i)
        fun values(key: String): String {
            if (!rule.has(key)) return ""
            val value = rule.opt(key)
            if (value is String) return value
            val array = value as? JSONArray ?: return ""
            return (0 until array.length()).map { array.getString(it) }.joinToString("\n")
        }
        RoutingRuleDraft(raw = rule.toString(), domains = values("domain"), ips = values("ip"),
            outbound = rule.optString("outboundTag"))
    }
}

private fun ruleDescription(rule: RoutingRuleDraft): String {
    val raw = JSONObject(rule.encoded())
    val parts = buildList {
        if (rule.domains.isNotBlank()) add("Домены: " + rule.domains.lines().filter { it.isNotBlank() }.joinToString(", "))
        if (rule.ips.isNotBlank()) add("IP: " + rule.ips.lines().filter { it.isNotBlank() }.joinToString(", "))
        for (field in listOf("inboundTag", "protocol", "network", "port", "source", "user")) {
            if (raw.has(field)) add("$field: ${raw.get(field)}")
        }
    }
    return parts.joinToString(" • ").ifBlank { "Условия не заданы" }
}

@Composable
private fun RoutingChoice(label: String, current: String, choices: List<Pair<String, String>>, enabled: Boolean = true, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var widthPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val palette = LocalXBoardPalette.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label.isNotBlank()) ServerUiTitle(label)
        Box(Modifier.fillMaxWidth().onSizeChanged { widthPx = it.width }) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = palette.background,
                border = BorderStroke(1.dp, palette.line),
            ) {
                BoardTextButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(choices.firstOrNull { it.first == current }?.second ?: current.ifBlank { "По умолчанию" }, modifier = Modifier.weight(1f))
                        Text(if (expanded) "−" else "+")
                    }
                }
            }
            BoardDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false },
                modifier = if (widthPx > 0) Modifier.width(with(density) { widthPx.toDp() }) else Modifier) {
                choices.forEach { (value, title) ->
                    BoardDropdownMenuItem(text = { Text(title) }, onClick = { expanded = false; onSelect(value) })
                }
            }
        }
    }
}

@Composable
internal fun ServerRoutingScreen(server: PanelConfig, onBack: () -> Unit) {
    val api = remember { PanelApi() }
    val scope = rememberCoroutineScope()
    val palette = LocalXBoardPalette.current
    var snapshot by remember(server.id) { mutableStateOf<XrayRoutingSnapshot?>(null) }
    var inboundLabels by remember(server.id) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var rules by remember(server.id) { mutableStateOf<List<RoutingRuleDraft>>(emptyList()) }
    val latestRules by rememberUpdatedState(rules)
    var editing by remember(server.id) { mutableStateOf<RoutingRuleDraft?>(null) }
    var newlyAdded by remember(server.id) { mutableStateOf<String?>(null) }
    var loading by remember(server.id) { mutableStateOf(true) }
    var saving by remember(server.id) { mutableStateOf(false) }
    var error by remember(server.id) { mutableStateOf<String?>(null) }
    var message by remember(server.id) { mutableStateOf<String?>(null) }
    var globalChanges by remember(server.id) { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var customGlobals by remember(server.id) { mutableStateOf<Set<String>>(emptySet()) }
    var globalsExpanded by remember(server.id) { mutableStateOf(false) }
    var confirming by remember(server.id) { mutableStateOf(false) }
    var loadNumber by remember(server.id) { mutableStateOf(0) }
    val bounds = remember(server.id) { mutableStateMapOf<String, Rect>() }
    var dragId by remember(server.id) { mutableStateOf<String?>(null) }
    var targetId by remember(server.id) { mutableStateOf<String?>(null) }
    var dragOffset by remember(server.id) { mutableStateOf(0f) }
    var dragCenter by remember(server.id) { mutableStateOf(0f) }

    fun cancelEdit() {
        if (newlyAdded == editing?.id) rules = rules.filterNot { it.id == newlyAdded }
        editing = null
        newlyAdded = null
    }
    fun removeRule(id: String) {
        rules = rules.filterNot { it.id == id }
        bounds.remove(id)
        if (editing?.id == id) editing = null
        if (newlyAdded == id) newlyAdded = null
        confirming = false
        error = null
        message = null
    }
    fun finishEdit() {
        val draft = editing ?: return
        val invalidPrefix = (draft.domains.lines() + draft.ips.lines()).any {
            it.trim() in setOf("domain:", "full:", "keyword:", "regexp:", "geosite:", "geoip:")
        }
        when {
            invalidPrefix -> error = "Дополните выбранное условие: укажите домен, IP или название категории."
            runCatching { draft.encoded() }.isFailure -> error = "Проверьте формат attrs: требуется корректный JSON-объект."
            else -> {
                rules = rules.map { if (it.id == draft.id) draft else it }
                error = null
                editing = null
                newlyAdded = null
            }
        }
    }
    LaunchedEffect(server.id, loadNumber) {
        loading = true
        error = null
        try {
            val loaded = api.xrayRouting(server)
            inboundLabels = try { api.routingInboundLabels(server) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { emptyMap() }
            snapshot = loaded
            rules = routingRules(loaded)
            globalChanges = emptyMap()
            customGlobals = emptySet()
            editing = null
            newlyAdded = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            snapshot = null
            error = failure.message ?: "Не удалось загрузить настройки Xray."
        } finally {
            loading = false
        }
    }
    ServerUiScreen(
        onBack = { if (editing != null) cancelEdit() else onBack() },
        backEnabled = !saving,
        contentKey = server.id,
        title = "Правила маршрутизации",
    ) {
        ServerUiCaption("Правила проверяются сверху вниз. Для перемещения удерживайте свёрнутое правило и перетащите его.")
        if (loading) ServerUiCaption("Загрузка настроек с панели…")
        error?.let { ServerUiError(it) }
        message?.let { ServerUiNotice("Маршрутизация", it) }
        if (!loading && snapshot == null) ServerUiPrimaryButton("Повторить загрузку", { loadNumber++ })
        if (!loading && snapshot != null) {
            val template = JSONObject(snapshot!!.template)
            val outbounds = template.optJSONArray("outbounds") ?: JSONArray()
            val options = (0 until outbounds.length()).mapNotNull { outbounds.optJSONObject(it)?.optString("tag")?.takeIf(String::isNotBlank) }
            val routing = template.getJSONObject("routing")
            val inboundOptions = (snapshot!!.inboundTags + inboundLabels.keys + (0 until (template.optJSONArray("inbounds")?.length() ?: 0)).mapNotNull {
                template.optJSONArray("inbounds")?.optJSONObject(it)?.optString("tag")?.takeIf(String::isNotBlank)
            }).distinct()
            Surface(
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                color = palette.soft,
                border = BorderStroke(1.dp, palette.line),
            ) {
              Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BoardTextButton(onClick = { globalsExpanded = !globalsExpanded }, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        ServerUiTitle("Общие настройки")
                        Text(if (globalsExpanded) "Свернуть" else "Развернуть")
                    }
                }
                if (globalsExpanded) listOf(
                    Triple("domainStrategy", "Domain strategy", listOf("AsIs", "IPIfNonMatch", "IPOnDemand")),
                    Triple("domainMatcher", "Domain matcher", listOf("linear", "mph")),
                ).forEach { (field, label, values) ->
                    val value = if (globalChanges.containsKey(field)) globalChanges[field].orEmpty() else routing.optString(field)
                    val custom = field in customGlobals || (value.isNotEmpty() && value !in values)
                    RoutingChoice(label, if (custom) "__custom__" else value,
                        listOf("" to "По умолчанию") + values.map { it to it } + ("__custom__" to "Свой вариант"), !saving) { chosen ->
                        if (chosen == "__custom__") customGlobals = customGlobals + field
                        else {
                            customGlobals = customGlobals - field
                            globalChanges = globalChanges + (field to chosen.ifBlank { null })
                        }
                    }
                    if (custom) OutlinedTextField(value = value, onValueChange = { globalChanges = globalChanges + (field to it) },
                        modifier = Modifier.fillMaxWidth(), enabled = !saving, label = { Text(label) }, singleLine = true)
                }
              }
            }
            ServerUiTitle("Правила:")
            if (rules.isEmpty()) ServerUiCaption("Правил маршрутизации нет.")
            rules.forEachIndexed { index, rule ->
                key(rule.id) {
                    val isDragging = dragId == rule.id
                    val highlighted = targetId == rule.id && dragId != null && !isDragging
                    val dragModifier = Modifier.pointerInput(rule.id, editing == null, saving) {
                        if (!saving && editing == null) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    dragId = rule.id
                                    targetId = rule.id
                                    dragOffset = 0f
                                    dragCenter = bounds[rule.id]?.center?.y ?: 0f
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffset += amount.y
                                    val y = dragCenter + dragOffset
                                    targetId = latestRules.minByOrNull { candidate ->
                                        val center = if (candidate.id == dragId) dragCenter else bounds[candidate.id]?.center?.y
                                        if (center == null) Float.MAX_VALUE else kotlin.math.abs(center - y)
                                    }?.id
                                },
                                onDragEnd = {
                                    val list = latestRules.toMutableList()
                                    val from = list.indexOfFirst { it.id == dragId }
                                    val to = list.indexOfFirst { it.id == targetId }
                                    if (from >= 0 && to >= 0 && from != to) list.add(to, list.removeAt(from))
                                    rules = list
                                    dragId = null; targetId = null; dragOffset = 0f
                                },
                                onDragCancel = { dragId = null; targetId = null; dragOffset = 0f },
                            )
                        }
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth().then(dragModifier)
                            .onGloballyPositioned { if (dragId != rule.id) bounds[rule.id] = it.boundsInRoot() }
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer { if (isDragging) translationY = dragOffset },
                        shape = RoundedCornerShape(20.dp),
                        color = if (highlighted) palette.accent.copy(alpha = 0.18f) else palette.soft,
                        border = BorderStroke(if (highlighted) 2.dp else 1.dp, if (highlighted) palette.accent else palette.line),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                BoardTextButton(
                                     onClick = {
                                         if (editing?.id == rule.id) finishEdit()
                                         else if (editing == null) { editing = rule; error = null }
                                     },
                                     enabled = !saving && dragId == null && (editing == null || editing?.id == rule.id),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text("${index + 1}. ${ruleDescription(rule)}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        val tag = rule.outbound.ifBlank { JSONObject(rule.raw).optString("balancerTag").let { if (it.isBlank()) "не задан" else "балансировщик: $it" } }
                                        Text("Тег: $tag", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                BoardIconButton(
                                    onClick = { removeRule(rule.id) },
                                    enabled = !saving && dragId == null && (editing == null || editing?.id == rule.id),
                                    modifier = Modifier.size(48.dp).semantics { contentDescription = "Удалить правило ${index + 1}" },
                                ) { BoardIcon(BoardSymbol.Trash, palette.foreground, 22.dp) }
                                Box(
                                    modifier = Modifier.size(48.dp).semantics { contentDescription = "Удерживайте и перетащите правило ${index + 1}" },
                                    contentAlignment = Alignment.Center,
                                ) { Text("☰") }
                            }
                            editing?.takeIf { it.id == rule.id }?.let { draft ->
                                val raw = JSONObject(draft.raw)
                                val domainEditable = !raw.has("domain") || raw.optJSONArray("domain") != null || raw.opt("domain") is String
                                val ipEditable = !raw.has("ip") || raw.optJSONArray("ip") != null || raw.opt("ip") is String
                                val type = draft.field("type")
                                var customType by remember(draft.id) { mutableStateOf(type.isNotEmpty() && type != "field") }
                                RoutingChoice("Type", if (customType) "__custom__" else type,
                                    listOf("" to "По умолчанию", "field" to "field", "__custom__" to "Свой вариант"), !saving) { chosen ->
                                    customType = chosen == "__custom__"
                                    if (!customType) editing = draft.withField("type", chosen)
                                }
                                if (customType) OutlinedTextField(value = type, onValueChange = { editing = draft.withField("type", it) },
                                    modifier = Modifier.fillMaxWidth(), label = { Text("Type") }, singleLine = true)
                                val inboundEditable = !raw.has("inboundTag") || raw.optJSONArray("inboundTag") != null || raw.opt("inboundTag") is String
                                val selectedInbound = draft.field("inboundTag").lines().map(String::trim).filter(String::isNotEmpty)
                                var customInbound by remember(draft.id) { mutableStateOf("") }
                                RoutingChoice("InboundTag", "__select__", listOf("__select__" to
                                    (if (selectedInbound.isEmpty()) "По умолчанию" else selectedInbound.joinToString(", ")),
                                    "__clear__" to "По умолчанию") + (inboundOptions + selectedInbound).distinct().map { tag ->
                                        val name = inboundLabels[tag]?.let { "$it ($tag)" } ?: tag
                                        tag to (if (tag in selectedInbound) "Выбран: $name" else name)
                                    } + ("__custom__" to "Свой вариант"), !saving && inboundEditable) { chosen ->
                                    when (chosen) {
                                        "__clear__" -> editing = draft.withField("inboundTag", "")
                                        "__custom__", "__select__" -> Unit
                                        else -> editing = draft.withField("inboundTag", (if (chosen in selectedInbound) selectedInbound - chosen else selectedInbound + chosen).joinToString("\n"))
                                    }
                                }
                                OutlinedTextField(value = customInbound, onValueChange = { customInbound = it },
                                    modifier = Modifier.fillMaxWidth(), enabled = !saving && inboundEditable,
                                    label = { Text("Свой inboundTag") }, singleLine = true)
                                BoardTextButton(onClick = {
                                    val tag = customInbound.trim()
                                    if (tag.isNotEmpty()) editing = draft.withField("inboundTag", (selectedInbound + tag).distinct().joinToString("\n"))
                                    customInbound = ""
                                }, enabled = !saving && inboundEditable && customInbound.isNotBlank()) { Text("Добавить inboundTag") }
                                ServerUiCaption("Условия одного правила действуют совместно. Для отдельной блокировки доменов и IP создайте два правила.")
                                RoutingChoice("", "", listOf(
                                    "" to "Выбор условия",
                                    "domain:|domain:" to "Домен и поддомены",
                                    "domain:|full:" to "Точный домен",
                                    "domain:|keyword:" to "Ключевое слово",
                                    "domain:|regexp:" to "Регулярное выражение",
                                    "domain:|geosite:" to "Категория geosite",
                                    "domain:|geosite:category-ads-all" to "Реклама: category-ads-all",
                                    "ip:|address" to "IP-адрес или CIDR",
                                    "ip:|geoip:" to "Категория geoip",
                                    "ip:|geoip:private" to "Локальные сети: geoip:private",
                                ), !saving) { chosen ->
                                    val value = chosen.substringAfter('|', "")
                                    when {
                                        chosen.startsWith("domain:|") && domainEditable -> editing = draft.copy(domains = listOf(draft.domains.trimEnd(), value).filter { it.isNotEmpty() }.joinToString("\n"), domainsChanged = true)
                                        chosen.startsWith("ip:|") && ipEditable && value != "address" -> editing = draft.copy(ips = listOf(draft.ips.trimEnd(), value).filter { it.isNotEmpty() }.joinToString("\n"), ipsChanged = true)
                                    }
                                }
                                OutlinedTextField(value = draft.domains, onValueChange = { editing = draft.copy(domains = it, domainsChanged = true) },
                                    modifier = Modifier.fillMaxWidth(), enabled = !saving && domainEditable,
                                    label = { Text("Домены, по одному в строке") })
                                OutlinedTextField(value = draft.ips, onValueChange = { editing = draft.copy(ips = it, ipsChanged = true) },
                                    modifier = Modifier.fillMaxWidth(), enabled = !saving && ipEditable,
                                    label = { Text("IP или сети, по одному в строке") })
                                if (!domainEditable || !ipEditable) ServerUiCaption("Необычный формат исходного поля сохранён без изменений.")
                                OutlinedTextField(value = draft.field("port"), onValueChange = { editing = draft.withField("port", it) },
                                    modifier = Modifier.fillMaxWidth(), enabled = !saving, label = { Text("Port") }, singleLine = true)
                                OutlinedTextField(value = draft.field("sourcePort"), onValueChange = { editing = draft.withField("sourcePort", it) },
                                    modifier = Modifier.fillMaxWidth(), enabled = !saving, label = { Text("Source port") }, singleLine = true)
                                val network = draft.field("network")
                                var customNetwork by remember(draft.id) { mutableStateOf(network.isNotEmpty() && network !in listOf("tcp", "udp", "tcp,udp")) }
                                RoutingChoice("Network", if (customNetwork) "__custom__" else network,
                                    listOf("" to "По умолчанию", "tcp" to "tcp", "udp" to "udp", "tcp,udp" to "tcp,udp", "__custom__" to "Свой вариант"), !saving) { chosen ->
                                    customNetwork = chosen == "__custom__"
                                    if (!customNetwork) editing = draft.withField("network", chosen)
                                }
                                if (customNetwork) OutlinedTextField(value = network, onValueChange = { editing = draft.withField("network", it) },
                                    modifier = Modifier.fillMaxWidth(), enabled = !saving, label = { Text("Network") }, singleLine = true)
                                val protocols = draft.field("protocol").lines().map(String::trim).filter(String::isNotEmpty)
                                RoutingChoice("Protocol", "__select__", listOf("__select__" to
                                    (if (protocols.isEmpty()) "По умолчанию" else protocols.joinToString(", ")),
                                    "__clear__" to "По умолчанию") + listOf("http", "tls", "quic", "bittorrent").map { it to if (it in protocols) "Выбран: $it" else it }, !saving) { chosen ->
                                    if (chosen == "__clear__") editing = draft.withField("protocol", "")
                                    else if (chosen != "__select__") editing = draft.withField("protocol", (if (chosen in protocols) protocols - chosen else protocols + chosen).joinToString("\n"))
                                }
                                OutlinedTextField(value = draft.field("protocol"), onValueChange = { editing = draft.withField("protocol", it) },
                                    modifier = Modifier.fillMaxWidth(), enabled = !saving, label = { Text("Protocol: свой вариант, по строкам") })
                                OutlinedTextField(value = draft.field("user"), onValueChange = { editing = draft.withField("user", it) },
                                    modifier = Modifier.fillMaxWidth(), enabled = !saving, label = { Text("User, по одному в строке") })
                                val sourceKey = if (raw.has("source") && !raw.has("sourceIP")) "source" else "sourceIP"
                                OutlinedTextField(value = draft.field(sourceKey), onValueChange = { editing = draft.withField(sourceKey, it) },
                                    modifier = Modifier.fillMaxWidth(), enabled = !saving, label = { Text("Source IP, по одному в строке") })
                                OutlinedTextField(value = draft.field("attrs"), onValueChange = { editing = draft.withField("attrs", it) },
                                    modifier = Modifier.fillMaxWidth(), enabled = !saving, label = { Text("Attrs: JSON-объект") })
                                val balancers = routing.optJSONArray("balancers") ?: JSONArray()
                                val balancerOptions = (0 until balancers.length()).mapNotNull { balancers.optJSONObject(it)?.optString("tag")?.takeIf(String::isNotBlank) }
                                val balancer = draft.field("balancerTag")
                                var customTarget by remember(draft.id) { mutableStateOf(false) }
                                val currentTarget = if (balancer.isNotBlank()) "balancer:$balancer" else draft.outbound
                                RoutingChoice("Действие / тег", if (customTarget) "__custom__" else currentTarget,
                                    listOf("" to "По умолчанию") + options.map { it to it } +
                                        balancerOptions.map { "balancer:$it" to "Балансировщик: $it" } + ("__custom__" to "Свой вариант"), !saving) { chosen ->
                                    customTarget = chosen == "__custom__"
                                    if (!customTarget && chosen.startsWith("balancer:")) editing = draft.copy(outbound = "", outboundChanged = true).withField("balancerTag", chosen.removePrefix("balancer:"))
                                    else if (!customTarget) editing = draft.copy(outbound = chosen, outboundChanged = true).withField("balancerTag", "")
                                }
                                if (customTarget || (draft.outbound.isNotBlank() && draft.outbound !in options && balancer.isBlank())) {
                                    OutlinedTextField(value = draft.outbound, onValueChange = { editing = draft.copy(outbound = it, outboundChanged = true).withField("balancerTag", "") },
                                        modifier = Modifier.fillMaxWidth(), enabled = !saving, label = { Text("Свой outboundTag") }, singleLine = true)
                                }
                                if (customTarget) OutlinedTextField(value = balancer, onValueChange = { editing = draft.copy(outbound = "", outboundChanged = true).withField("balancerTag", it) },
                                    modifier = Modifier.fillMaxWidth(), enabled = !saving, label = { Text("Свой balancerTag") }, singleLine = true)
                                val other = JSONObject(draft.raw).apply { remove("domain"); remove("ip"); remove("outboundTag") }
                                if (other.length() > 0) ServerUiCaption("Остальные параметры сохраняются: ${other}")
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    BoardTextButton(onClick = ::cancelEdit, enabled = !saving) { Text("Назад") }
                                    BoardTextButton(onClick = ::finishEdit, enabled = !saving) { Text("Сохранить") }
                                    BoardTextButton(onClick = { removeRule(draft.id) }, enabled = !saving) { Text("Удалить") }
                                }
                            }
                        }
                    }
                }
            }
            ServerUiPrimaryButton("Добавить правило", {
                val blocked = (0 until outbounds.length()).mapNotNull { outbounds.optJSONObject(it) }
                    .firstOrNull { it.optString("protocol") == "blackhole" }?.optString("tag").orEmpty()
                val raw = JSONObject().put("type", "field").put("outboundTag", blocked)
                val draft = RoutingRuleDraft(raw = raw.toString(), domains = "", ips = "", outbound = blocked, isNew = true)
                rules = rules + draft
                newlyAdded = draft.id
                editing = draft
            }, enabled = !saving && editing == null)
            ServerUiPrimaryButton("Обновить список", { loadNumber++; message = null }, enabled = !saving && editing == null)
            if (!confirming) {
                ServerUiPrimaryButton("Сохранить и обновить", { confirming = true }, enabled = !saving && editing == null)
            } else {
                ServerUiCaption("Сохранить правила и перезапустить Xray и панель? Активные соединения могут прерваться.")
                ServerUiPrimaryButton("Подтвердить", {
                    confirming = false
                    saving = true
                    error = null
                    message = null
                    val started = SystemClock.elapsedRealtime()
                    val base = snapshot!!
                    val submitted = rules.map { it.encoded() }
                    val submittedGlobals = globalChanges.toMap()
                    scope.launch {
                        var stage = "сохранения"
                        try {
                            rules.forEachIndexed { index, rule ->
                                val hasCondition = rule.domains.isNotBlank() || rule.ips.isNotBlank() ||
                                    listOf("port", "sourcePort", "network", "protocol", "user", "inboundTag", "sourceIP", "source", "attrs").any { rule.field(it).isNotBlank() }
                                require(!rule.isNew || hasCondition) { "В новом правиле ${index + 1} укажите хотя бы одно условие." }
                                require(!(rule.isNew || rule.outboundChanged || "balancerTag" in rule.extra) ||
                                    (rule.outbound.isNotBlank() xor rule.field("balancerTag").isNotBlank())) {
                                    "В правиле ${index + 1} укажите outboundTag или balancerTag."
                                }
                            }
                            require(submittedGlobals.values.none { it != null && it.isBlank() }) { "Укажите значение общей настройки или выберите значение по умолчанию." }
                            api.saveXrayRouting(server, base, submitted, submittedGlobals)
                            stage = "перезапуска Xray"
                            api.restartXrayForRouting(server)
                            stage = "перезапуска панели"
                            api.restartPanelForRouting(server)
                            stage = "проверки после перезапуска"
                            var fresh: XrayRoutingSnapshot? = null
                            delay(6_000)
                            repeat(10) {
                                if (fresh == null) {
                                    delay(2_000)
                                    try { fresh = api.xrayRouting(server) }
                                    catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { }
                                }
                            }
                            val verified = checkNotNull(fresh) { "Панель пока недоступна после перезапуска." }
                            check(api.routingRulesMatch(verified, submitted, submittedGlobals)) { "Настройки на панели отличаются от сохранённых." }
                            snapshot = verified
                            rules = routingRules(verified)
                            globalChanges = emptyMap()
                            customGlobals = emptySet()
                            editing = null
                            message = "Настройки обновлены. Панель снова доступна."
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = "Не удалось подтвердить этап $stage: ${failure.message ?: "ошибка связи с панелью"}. Настройки могли уже измениться; обновите их перед повтором."
                        } finally {
                            val remaining = 3_000 - (SystemClock.elapsedRealtime() - started)
                            if (remaining > 0) delay(remaining)
                            saving = false
                        }
                    }
                }, enabled = !saving)
                BoardTextButton(onClick = { confirming = false }, enabled = !saving) { Text("Отмена") }
            }
        }
    }
    if (saving) {
        val progress = remember { Animatable(0f) }
        LaunchedEffect(Unit) { progress.animateTo(0.9f, tween(3_000)) }
        Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
            Surface(shape = RoundedCornerShape(24.dp), color = palette.background, border = BorderStroke(1.dp, palette.line)) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    ServerUiTitle("Обновление настроек")
                    LinearProgressIndicator(progress = { progress.value }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
