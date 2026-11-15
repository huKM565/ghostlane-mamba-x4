# ИлюхаLine: отдельный бренд и Mamba x4 на Windows

## Контекст

Android-форк уже запускает четыре Mamba-комнаты через Xray roundRobin и VLESS Mux/XUDP. Нужны отдельное имя и внешний вид, установка рядом с Ghostlane, а также та же четырёхкомнатная работа в Windows TUN. Реальные ключи остаются только в локальной подписке.

## Бренд и изоляция

- Видимое имя на Android и Windows: `ИлюхаLine`.
- Android applicationId: `ru.hukm.ilyukhaline`.
- Внутренние Kotlin/Java package names и существующие ссылки импорта не меняются.
- Windows package name: `ИлюхаLine`; получает собственный постоянный upgradeUuid, отличный от Ghostlane.
- Windows использует `%APPDATA%\ИлюхаLine`, а не общий `%APPDATA%\Olcbox`, поэтому настройки, подписки, логи и cache-file не конфликтуют с Ghostlane.
- APK сохраняется как `~/Загрузки/ИлюхаLine-1.0.472-mamba-x4.apk`.

## Цветовая схема

Интерфейс и раскладка остаются прежними. Меняются только общие Material/PkPalette-токены на Android и JVM/Windows:

- primary и основной accent: cyan `22D3EE`;
- primaryContainer: тёмно-бирюзовый `103743`;
- tertiary и второй accent: orange `FF8A65`;
- соответствующие soft/container-цвета используют те же оттенки с существующей прозрачностью;
- тёмный фон, текст, success, warning и error сохраняются.

Это отличает ИлюхаLine от indigo/lime Ghostlane без изменения поведения компонентов.

## Windows Mamba x4

Пул выбирается только для olcRTC-профиля с непустым `bondVlessLink` в режиме Windows TUN.

1. Получить четыре уникальные комнаты из существующей модели и четыре свободных loopback-порта.
2. Для каждой комнаты запустить отдельный pinned olcRTC Windows process с общими SOCKS credentials, пустыми direct rules и выключенным native UDP.
3. Дождаться SOCKS-ready каждого процесса. При любой ошибке остановить весь пул.
4. Запустить Xray 26.7.11 с существующим `XrayConfig.buildVlessBond` на отдельном loopback-порту.
5. Запустить sing-box SOCKS-chain перед Xray и направить существующий Windows TUN на sing-box.
6. Профиль считается работающим только при живых четырёх olcRTC, Xray, sing-box и Windows TUN.

Остановка, смена профиля и отмена запуска останавливают все четыре процесса и оба core, удаляют их временные конфиги и освобождают порты. Watchdog учитывает весь пул, а не один процесс.

Обычные Mamba, failover `##rooms`, VK Calls и обычные VLESS используют прежний путь. Переключение Mamba x4 -> VK Calls -> Mamba x4 не оставляет старых процессов.

## Проверки

- Тесты брендинга проверяют Android applicationId/имя, Windows package/upgradeUuid/папку настроек и общие цветовые токены.
- Существующие JVM-тесты и тесты подписки, Xray и жизненного цикла должны проходить.
- Новые desktop-тесты проверяют четыре комнаты/порта, отсутствие direct rules и native UDP, запуск обоих core и полную очистку.
- Реальный Xray smoke повторно подтверждает roundRobin и UDP.
- APK проверяется через aapt, apksigner и zipalign, затем устанавливается рядом с Ghostlane через adb.
- На Android проверяются реальные четыре комнаты, TCP/UDP и переключения с VK Calls.

## Windows-пакет

Windows release собирается на `windows-latest` через GitHub Actions: pinned olcRTC, sing-box и Xray, `packageReleaseExe`, `packageReleaseMsi`, portable ZIP. Без доступного code-signing сертификата пакеты явно поставляются как unsigned test builds. Артефакты проверяются на правильное имя и наличие нативных компонентов.

## Не входит

- Перенос пула на Linux/macOS/iOS.
- Попакетный бондинг одного TCP-соединения.
- Изменение серверного протокола или раскладки UI.
- Автоматическое удаление или перезапись установленного Ghostlane.
