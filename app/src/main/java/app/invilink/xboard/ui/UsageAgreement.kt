package app.invilink.xboard.ui

import androidx.activity.compose.BackHandler
import app.invilink.xboard.BuildConfig
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import app.invilink.xboard.R

internal const val AGREEMENT_VERSION = 1

internal data class AgreementSection(val heading: String, val body: String)

internal val agreementSections: List<AgreementSection> = listOf(
    AgreementSection(
        "1. Назначение и принятие условий",
        "3xBoard — инструмент управления панелями 3x-UI и серверами, доступ к которым предоставлен пользователю на законном основании. Настоящие условия описывают допустимое использование, технические риски и обработку данных. Автором далее именуется разработчик приложения. Перед началом работы прочитайте все разделы. Отметка «Принять и начать» и последующее нажатие «Продолжить» означают явное принятие этой редакции. Если условия неприемлемы, выберите «Выйти». Принятие не требует покупки, подписки или согласия на рекламу и не заменяет отдельные разрешения Android."
    ),
    AgreementSection(
        "2. Соблюдение законодательства",
        "Используйте приложение исключительно в законных целях. Учитывайте применимое право всех относящихся к операции юрисдикций: места вашего нахождения, размещения сервера и обработки данных, а также обязательные правила провайдера. Само наличие технической функции не означает, что её применение разрешено. До подключения, установки или передачи данных самостоятельно выясните необходимые ограничения, согласия и основания. Запрещено использовать 3xBoard для противоправного распространения информации, мошенничества, нарушения чужих прав, атак, незаконного наблюдения и иных запрещённых действий. При сомнениях приостановите операцию и обратитесь за квалифицированной консультацией."
    ),
    AgreementSection(
        "3. Владение VPS и разрешение на доступ",
        "Подключайте только собственные VPS, серверы и панели либо ресурсы, которыми вы вправе управлять по явно полученному разрешению владельца или уполномоченного администратора. Разрешение должно охватывать конкретные действия, включая изменение конфигурации, установку компонентов, перезагрузку и удаление. Знание адреса, пароля, ключа SSH или токена само по себе не доказывает наличие полномочий. Несанкционированный доступ, подбор учётных данных, эксплуатация чужих уязвимостей и превышение выданных прав недопустимы. При отзыве доступа прекратите управление и удалите сохранённые секреты в пределах своих законных обязанностей."
    ),
    AgreementSection(
        "4. Сетевые ограничения и правовая оценка",
        "Создание туннелей, использование прокси, шифрования и обход сетевых блокировок могут регулироваться или ограничиваться местным законодательством, договором подключения и правилами организации. Ответственность за выбор допустимого способа применения несёт пользователь в пределах применимого права. Приложение не предоставляет разрешения обходить запреты и не гарантирует анонимность, неотслеживаемость или освобождение от ответственности. Автор не является вашим юридическим советником; текст соглашения и интерфейс не заменяют индивидуальную правовую консультацию. Никакие формулировки здесь не обещают правового иммунитета и не отменяют обязательных требований закона."
    ),
    AgreementSection(
        "5. Административные операции и резервные копии",
        "Установка и обновление 3x-UI, выполнение команд SSH, изменение сетевых правил, перезагрузка и удаление компонентов способны прервать соединения, остановить сервисы и необратимо уничтожить конфигурации, сертификаты, клиентов, журналы и иные данные. Часть действий требует root или сопоставимых привилегий и затрагивает весь сервер. Перед подтверждением проверьте адрес назначения, объём полномочий и последствия. Создайте независимую резервную копию и проверьте возможность восстановления. Приложение не заменяет систему резервного копирования. Прерванная операция может выполниться частично; повторение команды без проверки состояния способно усугубить последствия."
    ),
    AgreementSection(
        "6. Соединения, ключи и сертификаты",
        "При подключении проверяйте подлинность сервера: отпечаток ключа SSH, домен, цепочку доверия и срок действия сертификата TLS. Сверяйте новый или изменившийся ключ SSH с независимым доверенным источником; неожиданная замена может указывать на переустановку сервера либо атаку посредника. Не обходите ошибки проверки сертификатов ради удобства. Защита соединения зависит также от DNS, настроек сервера, сети и актуальности системы. Для установки пакетов и выпуска сертификатов ACME может потребоваться доступ сервера в Интернет, корректные DNS-записи и доступность проверяемых портов. Выпуск сертификата не гарантируется."
    ),
    AgreementSection(
        "7. Локальное хранение данных",
        "3xBoard хранит на устройстве зашифрованные конфигурации подключения и токены; при выбранном пользователем сохранении SSH-учётных данных сохраняются также соответствующие секреты, включая пароли или ключевой материал. Локально могут находиться кэш панелей и клиентов, адреса серверов, параметры интерфейса и очередь отложенных изменений для офлайн-работы. Эти сведения могут раскрывать структуру инфраструктуры и требуют защиты даже без содержимого сетевого трафика. Не сохраняйте секреты на чужом устройстве. Проверяйте настройки резервного копирования Android и доступ сторонних приложений; удаление записи не гарантирует стирания ранее сделанных копий."
    ),
    AgreementSection(
        "8. PIN-код, биометрия и Android Keystore",
        "Защита локальных секретов использует механизмы Android Keystore и предусмотренную приложением проверку PIN-кода или биометрии. Биометрические шаблоны обрабатываются средствами Android; приложение не должно получать изображение отпечатка или лица вместо результата системной проверки. Доступность и стойкость зависят от устройства, его блокировки и состояния системы. PIN-код приложения не заменяет сложный пароль устройства и контроль физического доступа. Root-доступ, вредоносное ПО, разблокированный загрузчик, захват экрана и компрометация ОС могут снизить защиту. Автор не обещает невозможности извлечения данных или стопроцентной безопасности."
    ),
    AgreementSection(
        "9. Передача данных при работе с серверами",
        "Для выполнения выбранных действий приложение обращается к указанным вами панелям и VPS. На удалённый сервер передаются необходимые параметры запросов, конфигурации, команды и данные аутентификации через соответствующие защищённые соединения, включая SSH. Сервер и его администраторы могут сохранять полученные сведения и журналы. DNS-запросы передают разрешаемые имена используемому резолверу; TLS защищает содержимое соединения при корректной проверке, но не скрывает все сетевые метаданные. Состав передаваемых данных определяется операцией. Не отправляйте сведения третьих лиц без законного основания и необходимых уведомлений или согласий."
    ),
    AgreementSection(
        "10. Геолокация IP и внешние сервисы",
        "При запросе сведений о местоположении публичный IP-адрес соответствующего сервера передаётся сервису ipwho.is; сервис также видит технические данные своего сетевого соединения, например адрес источника запроса. Это приблизительное определение местоположения IP, а не гарантия физического расположения сервера. Установка, загрузка выпусков с GitHub и запросы к центрам сертификации ACME вовлекают соответствующие внешние сервисы. Если в будущей версии появится необязательная проверка обновлений через GitHub, она также потребует сетевого обращения. Пока адрес проверки не настроен, кнопка проверки не означает наличия работающего сервиса обновлений."
    ),
    AgreementSection(
        "11. Приватность и границы контроля",
        "В 3xBoard не добавлены рекламные и аналитические SDK, продажа пользовательских данных, профилирование пользователей или отдельный авторский backend для сбора таких сведений. Это не означает отсутствия любых внешних передач: подключения к вашим серверам и явно описанным сторонним сервисам необходимы для соответствующих функций. ОС, магазин приложений, DNS-провайдер, хостинг, сетевой оператор и внешние сервисы могут вести собственные журналы по своим правилам и закону. Автор не управляет их инфраструктурой и не может гарантировать отсутствие таких записей. Содержимое обращений в поддержку определяется тем, что вы отправляете самостоятельно."
    ),
    AgreementSection(
        "12. Офлайн-режим и отложенные изменения",
        "Кэшированные сведения могут быть устаревшими и не отражать фактическое состояние сервера. Действия, сохранённые в локальной очереди, могут быть переданы и применены после восстановления соединения в соответствии с поведением синхронизации. До синхронизации проверьте адрес назначения, актуальность полномочий и возможные конфликты с изменениями других администраторов. Отсутствие ответа не доказывает, что команда не была выполнена. Не повторяйте разрушительные действия вслепую. Потеря устройства, очистка данных приложения или сбой накопителя могут привести к утрате локальной очереди и несохранённых изменений."
    ),
    AgreementSection(
        "13. Обязанности пользователя и данные других лиц",
        "Вы самостоятельно выбираете серверы, тарифы и параметры доступа, обеспечиваете своевременное обновление инфраструктуры, резервное копирование и законность обрабатываемых данных. Ограничивайте привилегии и срок действия токенов; при подозрении на утечку отзовите их и замените пароли и ключи на стороне сервера. Не передавайте автору действующие секреты вместе с отчётами об ошибках. Если вы управляете данными клиентов или сотрудников, определите свои обязанности оператора либо обработчика по применимому праву. Ответственность пользователя устанавливается только в допустимом законом объёме и не включает автоматическое принятие любых чужих рисков."
    ),
    AgreementSection(
        "14. Предоставление «как есть»",
        "В пределах, допускаемых применимым правом, приложение предоставляется «как есть» и по фактической доступности, без обещания непрерывной работы, совместимости со всеми версиями 3x-UI, пригодности для любой задачи или отсутствия ошибок. Не гарантируются стопроцентная безопасность, доступность сетей, сохранность всех данных и успешность установки или восстановления. Работа зависит от стороннего ПО, оборудования и настроек. Для критически важных систем проведите собственную оценку рисков и предусмотрите альтернативный способ администрирования. Этот раздел не исключает обязательные гарантии и права потребителя, которые не могут быть ограничены соглашением."
    ),
    AgreementSection(
        "15. Пределы ответственности",
        "Ограничения ответственности автора действуют исключительно в той мере, в какой они разрешены применимым правом. Настоящее соглашение не устанавливает абсолютного освобождения автора от любой ответственности и не исключает предусмотренную законом ответственность, включая случаи, когда её ограничение запрещено, а также обязательные средства защиты пользователя. Причинение убытков, наличие вины, причинная связь и размер возмещения оцениваются по применимому закону. Пользователь отвечает за собственные действия и решения в установленных законом пределах. Ничто здесь не обязывает отказываться от неотчуждаемых прав или принимать незаконные условия."
    ),
    AgreementSection(
        "16. Стороннее ПО и открытые лицензии",
        "3x-UI является отдельным сторонним проектом; его распространение и использование регулируются его лицензией GNU GPL и относящимися к поставке уведомлениями. Прочие библиотеки, установочные сценарии, операционные системы и сервисы имеют собственные лицензии и условия. Проверяйте происхождение загружаемых выпусков GitHub и права на их использование. Соглашение 3xBoard не заменяет, не сужает и не отменяет права, предоставленные GPL и другими применимыми открытыми лицензиями, включая предусмотренные ими права на исходный код, изменение и распространение. Условия внешних сервисов применяются к взаимодействию с ними отдельно."
    ),
    AgreementSection(
        "17. Изменения условий и прекращение использования",
        "Текст имеет номер редакции, показанный в приложении. Существенно изменённые условия должны быть представлены для нового явного принятия; принятие текущей редакции не означает заранее данного согласия с любыми будущими изменениями. Вы можете прекратить использование, удалить локальные данные и отозвать доступ на серверах. Удаление приложения не удаляет автоматически удалённые панели, учётные записи, резервные копии или журналы сторонних сервисов. Если отдельное условие неприменимо, остальные действуют лишь в допустимых законом пределах. Обязательные нормы и открытые лицензии сохраняют приоритет в пределах их предмета регулирования."
    )
)

@Composable
internal fun AgreementDialog(onDismiss: () -> Unit) {
    val palette = LocalXBoardPalette.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            securePolicy = SecureFlagPolicy.SecureOn,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        SessionDialogGuard()
        BoxWithConstraints(Modifier.padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
            val dialogHeight = maxHeight * 0.9f
            Surface(
                modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().heightIn(max = dialogHeight)
                    .semantics {
                        paneTitle = "Соглашение об использовании"
                        isTraversalGroup = true
                    },
                shape = RoundedCornerShape(28.dp),
                color = palette.background,
                border = BorderStroke(1.dp, palette.line),
                shadowElevation = 20.dp
            ) {
                Column {
                    Column(Modifier.fillMaxWidth().background(palette.soft).padding(24.dp)) {
                        Text(
                            "Соглашение об использовании",
                            modifier = Modifier.semantics { heading() },
                            style = MaterialTheme.typography.titleLarge,
                            color = palette.foreground
                        )
                        Spacer(Modifier.height(4.dp))
                        Text("Редакция для версии ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium, color = palette.secondary)
                    }
                    AgreementText(
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)
                    )
                    BoardButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp).heightIn(min = 52.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.onAccent),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)
                    ) {
                        Text("Закрыть", textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Composable
internal fun FirstRunAgreementScreen(onAccept: () -> Unit, onExit: () -> Unit) {
    val palette = LocalXBoardPalette.current
    var accepted by remember { mutableStateOf(false) }
    BackHandler(onBack = onExit)
    Surface(Modifier.fillMaxSize(), color = palette.background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val footerHeight = maxHeight * 0.45f
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    Modifier.widthIn(max = 808.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(painterResource(R.drawable.xboard_icon), contentDescription = null, modifier = Modifier.size(44.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("3xBoard", style = MaterialTheme.typography.titleLarge, color = palette.foreground)
                        Text("Редакция для версии ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium, color = palette.secondary)
                    }
                }
                Box(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Column(Modifier.widthIn(max = 808.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
                        Text(
                            "Соглашение об использовании",
                            modifier = Modifier.semantics { heading() },
                            style = MaterialTheme.typography.headlineMedium,
                            color = palette.foreground
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Прочитайте условия. Продолжение доступно только после вашего явного согласия.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.secondary
                        )
                        Spacer(Modifier.height(24.dp))
                        AgreementText()
                    }
                }
                Column(
                    Modifier.widthIn(max = 808.dp).fillMaxWidth().heightIn(max = footerHeight)
                        .background(palette.background).verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(palette.line))
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                            .toggleable(value = accepted, role = Role.Checkbox, onValueChange = { accepted = it })
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = accepted,
                            onCheckedChange = null,
                            modifier = Modifier.size(48.dp),
                            colors = CheckboxDefaults.colors(checkedColor = palette.accent, checkmarkColor = palette.onAccent)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Принять и начать",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            color = palette.foreground
                        )
                    }
                    BoardButton(
                        onClick = { if (accepted) onAccept() },
                        enabled = accepted,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.onAccent),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)
                    ) {
                        Text("Продолжить", textAlign = TextAlign.Center)
                    }
                    BoardTextButton(
                        onClick = onExit,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = palette.foreground),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
                    ) {
                        Text("Выйти", textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Composable
private fun AgreementText(modifier: Modifier = Modifier) {
    val palette = LocalXBoardPalette.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        agreementSections.forEach { section ->
            Text(
                section.heading,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
                color = palette.foreground
            )
            Text(section.body, style = MaterialTheme.typography.bodyMedium, color = palette.secondary)
            Spacer(Modifier.height(8.dp))
        }
    }
}
