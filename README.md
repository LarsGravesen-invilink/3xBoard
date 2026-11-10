# 3xBoard

Android-приложение для управления панелями 3x-ui и серверами по SSH.

## Возможности

- Управление панелями, инбаундами и клиентами.
- Создание и управление каскадами, настройка маршрутизации.
- SSH-инструменты и защита доступа к WebUI панелей.
- Локальная защита приложения и настраиваемый интерфейс.

## Сборка

Требуются JDK 17 и Android SDK с платформой API 35. Приложение поддерживает Android 10 и новее.

```sh
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Для локальных JVM-тестов также нужны исходники Android SDK `sources;android-37.0`:

```sh
./gradlew :app:testDebugUnitTest
```

Сборка debug подписывается отладочным ключом; для release необходим собственный защищённый ключ подписи. GitHub Actions запускается вручную и создаёт debug-артефакт.

## Лицензия

[MIT](LICENSE). При распространении автор просит указывать источник проекта; эта просьба не является дополнительным условием лицензии.

---

[![Telegram](https://img.shields.io/badge/Telegram-LarsInvilink-26A5E4?style=for-the-badge&logo=telegram&logoColor=white)](https://t.me/LarsInvilink)
