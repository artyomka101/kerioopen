# OpenKerio VPN (Android)

Репозиторий: [artyomka101/kerioopen](https://github.com/artyomka101/kerioopen)

Открытый VPN-клиент для Android под **Kerio Control** (OpenControl, не IKEv2).

Протокол — [opencontrol-protocol](https://github.com/nr084/opencontrol-protocol) (MIT).  
Не связан с Kerio/GFI.

Обновления в приложении берутся из [GitHub Releases](https://github.com/artyomka101/kerioopen/releases) (тег вида `v1.3.0`).

## Возможности

- Сервер `host:port` (по умолчанию `4090`)
- Имя соединения + сохранённые профили
- Логин / пароль, показ пароля, Keystore
- Pin SHA-256 сертификата
- AES-GCM (Kerio 9.2.8+) и Blowfish legacy (≤ 9.2.7), режим Auto/ручной
- Rekey по CEP (без обрыва через ~15 мин)
- Устойчивость к краткому hiccup Wi‑Fi/LTE
- Лог сессии + экспорт
- Тема light/dark/system, RU/EN
- График скорости, uptime в уведомлении
- Проверка обновлений через GitHub Releases
- `VpnService` + уведомление

## Сборка

Android Studio / JDK 17 + SDK. NDK и Rust не нужны.

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug
```

APK: `app\build\outputs\apk\debug\app-debug.apk`

## Использование

1. Установить APK  
2. Сервер → **Проверить сертификат** → Доверять  
3. Логин/пароль → **Подключить**

## Структура

```
app/      UI, VpnService, Kotlin OpenControl engine
native/   опциональный Rust (в APK не входит)
scripts/  сборка native .so
```

Только IP-туннель: веб-фильтр как у Windows-клиента Kerio не эмулируется.
