# Исследование гибридных каскадов

Статус: исследование, не реализовано.

## Текущая схема каскада

Существующий мастер создаёт VLESS + REALITY inbound на входном и выходном узлах и VLESS outbound на входе. Для межсерверного участка доступны TCP и XHTTP. Следовательно, текущий каскад не выбирает независимые протоколы для публичного входа и межсерверного hop.

## Целевая модель

```
Клиент → публичный inbound входного узла → routing → межсерверный outbound → inbound выходного узла → интернет
```

Публичный inbound и межсерверный hop могут использовать разные протоколы. Однако протокол outbound на входном узле обязан соответствовать inbound на выходном. Создать два несвязанных inbound разных протоколов недостаточно: необходимы outbound и routing rule.

## Реалистичный первый вариант: VLESS/REALITY → Hysteria2

На выходе необходимо создать Hysteria2 inbound, TLS-сертификат, UDP-порт, технического клиента с уникальным `auth` и при необходимости Salamander/Gecko.

На входе необходим Hysteria outbound с адресом/UDP-портом выхода, `version: 2`, TLS/SNI/ALPN, `auth` и параметрами маскировки, совпадающими с выходом. Маршрут должен направлять трафик публичного entry inbound на этот outbound.

Требуются проверки до создания:

- обе панели и их Xray поддерживают Hysteria2 inbound/outbound;
- UDP-порт не занят в панели; firewall и провайдер пропускают UDP;
- TLS-сертификат, SNI и ALPN корректны;
- `auth` и Salamander password совпадают на обеих сторонах.

Риски: доступность UDP извне невозможно надёжно подтвердить только API панели; XHTTP/Vision не применяются к Hysteria hop; нужен отдельный health-check и rollback для технического клиента, inbound, outbound и routing rule.

## Более сложный вариант: VLESS/REALITY → AmneziaWG

Исследованная версия 3x-ui содержит специальный pseudo-outbound `amneziawg`, который панель заменяет локальным SOCKS bridge перед запуском Xray. Это не стандартный outbound Xray.

На выходе нужен AmneziaWG inbound и технический peer. На входе нужны AmneziaWG outbound, private key входной стороны, public key peer выхода, endpoint, адреса туннеля, allowed IPs и согласованные параметры обфускации.

Требуются проверки до создания:

- версия 3x-ui поддерживает не только AmneziaWG inbound, но и outbound bridge;
- на выходе готов AmneziaWG runtime/kernel или embedded implementation;
- совпадают ключи, peer-адреса и Jc/Jmin/Jmax, S1–S4, H1–H4, I1–I5;
- allowed IPs не создают routing loop и не направляют endpoint выхода внутрь туннеля;
- UDP-порт доступен извне.

Риски: функциональность зависит от версии и расширений 3x-ui, некорректные allowed IPs создают loop, требуется сложный rollback peer/outbound/routing. Реализовывать только после Hysteria2-hop и тестов на изолированных VPS.

## Необходимые будущие изменения приложения

1. Ввести отдельные варианты межсерверного hop: VLESS/REALITY TCP, VLESS/REALITY XHTTP, Hysteria2, AmneziaWG.
2. Отделить выбор публичного entry inbound от выбора hop-протокола.
3. Дополнить запрос и журнал каскада протоколом, параметрами TLS/Hysteria и защищёнными данными технических ключей/peer.
4. Добавить фабрики inbound/outbound для Hysteria2 и AmneziaWG вместо единственной VLESS/REALITY-фабрики.
5. Выполнять preflight capability check до изменений на панелях.
6. Расширить rollback: удалить технические clients/peers, outbound и routing rule, не затрагивая пользовательские сущности.
7. Добавить payload-, rollback- и интеграционные тесты для VLESS → Hysteria2, VLESS → AmneziaWG, Hysteria2 → VLESS.
