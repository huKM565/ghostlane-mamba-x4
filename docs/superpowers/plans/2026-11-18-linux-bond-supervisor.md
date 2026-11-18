# Linux Bond Supervisor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Запустить настоящие Mamba/VK/SaluteJazz 4x на Linux TUN через одного root-supervisor, с индикаторами, восстановлением комнат и отдельными настройками IlyukhaLine.

**Architecture:** Один Bash-supervisor, запущенный через один `LinuxPrivilege.command`, владеет всеми olcRTC и перезапускает упавшие. Kotlin использует существующие `DesktopOlcRtcBond`, Xray/sing-box и watcher; для Linux liveness читается из файлов состояния. Настройки переезжают в `~/.ilyukhaline` с однократной миграцией разрешённых файлов.

**Tech Stack:** Kotlin Multiplatform, JVM/NIO, Bash, Compose Desktop, JUnit/kotlin.test, polkit/pkexec, olcRTC, Xray, sing-box.

**Spec:** `docs/superpowers/specs/2026-11-18-linux-bond-supervisor-design.md`

## Global Constraints

- Один вызов `LinuxPrivilege.command` на весь пул, не по вызову на комнату или восстановление.
- Linux TUN и Windows TUN используют общий bonding-код; System Proxy, Android, macOS и обычные профили не меняются.
- Первая готовая комната поднимает туннель; Xray получает только готовые порты и никогда пустой список.
- Остановка supervisor предшествует TUN cleanup; после начала очистки комнаты не перезапускаются.
- Linux использует `~/.ilyukhaline`; `~/.olcbox` не изменяется и не удаляется.
- Скрипт supervisor не содержит комнат, ключей или SOCKS-пароля: только пути к конфигам и runtime-каталогу.
- Новые внешние зависимости не добавляются. Новый код — без комментариев о причинах изменения, в существующем стиле файлов.
- Нагрузочное определение максимального числа Mamba-комнат, Multi, MTS и Телемост не входят в работу.

## Review Focus

- Прерванная миграция или повторный запуск: недостающие разрешённые файлы дополняются, изменённые пользователем не перезаписываются — Task 1.
- Зависший supervisor после неудачного stop-файла: его имя сохраняется для root-очистки, дочерние процессы не перезапускаются — Tasks 3–4.
- Ноль готовых комнат или отказ polkit: Xray не создаётся, частичный пул очищается без скрытого повторного pkexec — Task 4.
- Потеря одной комнаты: она уходит из active/Xray, остальные продолжают работать, после SOCKS-ready только она возвращается — Tasks 2–4 и Task 6.
- Отказ stop-файла во время завершения: TUN/kill switch остаются в существующем безопасном состоянии, supervisor и движки завершаются fallback-очисткой — Tasks 3–4 и Task 6.

---

### Task 1: Отдельный Linux-каталог и безопасная миграция

**Files:**
- Modify: `sharedUI/src/jvmMain/kotlin/org/olcbox/app/desktop/DesktopPaths.kt`
- Test: `sharedUI/src/jvmTest/kotlin/org/olcbox/app/desktop/DesktopBrandingTest.kt`

**Interfaces:**
- Consumes: `DesktopPaths.appDataDir(): Path` и существующие имена файлов настроек.
- Produces: Linux `~/.ilyukhaline`; однократный импорт из `~/.olcbox` с marker `.ilyukhaline-import-complete`.

- [ ] **Step 1: Написать failing tests**

Добавить тесты, которые подменяют `os.name=Linux` и `user.home` на временный каталог:

- `linuxDataDoesNotShareTheGhostlaneDirectory`: ожидает `home/.ilyukhaline`.
- `linuxMigrationCopiesOnlyApprovedFilesOnce`: создаёт в `.olcbox` `locations_v4.json`, `locations_v3.json`, `device_identity`, `desktop_socks_proxy_settings.json`, `desktop_connection_mode` и `runtime/secret.yaml`; после вызова проверяет копирование разрешённых файлов, отсутствие runtime и неизменный источник.
- `linuxMigrationRetriesMissingFilesWithoutOverwriting`: удаляет marker и один импортированный файл, изменяет другой; повторный вызов восстанавливает только отсутствующий.
- После marker повторный вызов не восстанавливает удалённые пользователем файлы.

- [ ] **Step 2: Подтвердить RED**

Run: `./gradlew :sharedUI:jvmTest --tests org.olcbox.app.desktop.DesktopBrandingTest`
Expected: FAIL: Linux всё ещё возвращает `.olcbox`, миграции нет.

- [ ] **Step 3: Реализовать каталог и миграцию**

В `DesktopPaths` для Linux выбрать `home.resolve(".ilyukhaline")`. До первого использования каталога вызвать приватную миграцию: при отсутствии marker создать каталог, скопировать только пять разрешённых имён без overwrite, затем записать marker. Windows/macOS не вызывают миграцию. Не копировать `update_settings.json`, runtime, кэши, бинарники и логи.

- [ ] **Step 4: Подтвердить GREEN и существующее поведение**

Run: `./gradlew :sharedUI:jvmTest --tests org.olcbox.app.desktop.DesktopBrandingTest --tests org.olcbox.app.data.datasource.LocationsRepositoryImplTest`
Expected: PASS; Windows identity и legacy/fresh routing сохранены.

- [ ] **Step 5: Commit**

```bash
git add sharedUI/src/jvmMain/kotlin/org/olcbox/app/desktop/DesktopPaths.kt sharedUI/src/jvmTest/kotlin/org/olcbox/app/desktop/DesktopBrandingTest.kt
git commit -m "feat: isolate linux app settings"
```

### Task 2: LinuxBondSupervisor и его shell-жизненный цикл

**Files:**
- Create: `sharedUI/src/jvmMain/kotlin/org/olcbox/app/vpn/desktop/LinuxBondSupervisor.kt`
- Create: `sharedUI/src/jvmTest/kotlin/org/olcbox/app/vpn/desktop/LinuxBondSupervisorTest.kt`

**Interfaces:**
- Consumes: `LinuxPrivilege.command(command: List<String>): List<String>`.
- Produces: `LinuxBondSupervisor(binary: Path, configPaths: List<Path>, runtimeDir: Path, privilege: (List<String>) -> List<String> = LinuxPrivilege::command, launcher: (List<String>) -> Process = defaultProcessLauncher)`. Тесты подменяют оба параметра, production использует defaults.
- Produces: `start(): Process`, `stop(timeoutMillis: Long = 5000)`, `scriptPath: Path`, `logPath(index: Int): Path`, `statePath(index: Int): Path`, `isRoomUp(index: Int): Boolean`, `deleteRuntime()`.
- Produces: `defaultProcessLauncher` создаёт `ProcessBuilder(command).redirectErrorStream(true)` и ставит `NO_PROXY`/`no_proxy=127.0.0.1,localhost` перед `start()`.

- [ ] **Step 1: Написать failing tests жизненного цикла**

Использовать временный fake `olcrtc` shell-скрипт, который обрабатывает TERM и работает в цикле. Через `privilege={it}` и настоящий `ProcessBuilder` launcher:

- `startsEveryRoomAndRestartsOnlyTheDeadOne`: дождаться четырёх разных PID, завершить PID комнаты 2, дождаться нового PID только у неё.
- `stopFileEndsSupervisorAndEveryRoom`: создать stop-файл через `stop()`, дождаться выхода и проверить, что все PID завершены.
- `startUsesOnePrivilegedInvocation`: recording launcher фиксирует ровно один вызов, содержащий `bash`, `scriptPath`, runtime, binary и четыре config path.
- `scriptEmbedsNoRoomOrSocksSecrets`: передать config path с именем `room-secret.yaml`; после `start()` проверить, что supervisor-скрипт не содержит ни это имя, ни `socks-secret`. Путь к YAML существует только в аргументах запуска, а не записывается в тело скрипта.

Тесты обязаны завершать процессы в `finally` и использовать condition polling вместо фиксированных долгих ожиданий.

- [ ] **Step 2: Подтвердить RED**

Run: `./gradlew :sharedUI:jvmTest --tests org.olcbox.app.vpn.desktop.LinuxBondSupervisorTest`
Expected: FAIL: класс отсутствует.

- [ ] **Step 3: Реализовать минимальный supervisor**

`start()` создаёт runtime и Bash-скрипт, затем один раз вызывает `launcher(privilege(command))`. Скрипт принимает runtime, binary и конфиги; пишет `room-N.log`, `room-N.state`, `room-N.pid`; перезапускает умерший процесс через 2 секунды; по `stop`, TERM и INT отключает перезапуск и завершает все детей. После TERM посылает KILL через 3 секунды при необходимости.

`stop()` создаёт stop-файл и ждёт процесс; если он не завершился за timeout, выбрасывает `IllegalStateException`, не удаляя runtime и не делая скрытый повтор. `deleteRuntime()` вызывается только после подтверждённой остановки. `isRoomUp` читает `up` из соответствующего state-файла и возвращает false при отсутствии/ошибке чтения.

- [ ] **Step 4: Подтвердить GREEN**

Run: `./gradlew :sharedUI:jvmTest --tests org.olcbox.app.vpn.desktop.LinuxBondSupervisorTest`
Expected: все lifecycle tests PASS, после теста нет fake olcRTC процессов.

- [ ] **Step 5: Commit**

```bash
git add sharedUI/src/jvmMain/kotlin/org/olcbox/app/vpn/desktop/LinuxBondSupervisor.kt sharedUI/src/jvmTest/kotlin/org/olcbox/app/vpn/desktop/LinuxBondSupervisorTest.kt
git commit -m "feat: add linux bond supervisor"
```

### Task 3: Liveness пула и fallback-очистка процессов

**Files:**
- Modify: `sharedUI/src/jvmMain/kotlin/org/olcbox/app/vpn/desktop/DesktopOlcRtcBond.kt`
- Modify: `sharedUI/src/jvmMain/kotlin/org/olcbox/app/vpn/desktop/LinuxTunController.kt`
- Test: `sharedUI/src/jvmTest/kotlin/org/olcbox/app/vpn/desktop/DesktopOlcRtcBondTest.kt`
- Create: `sharedUI/src/jvmTest/kotlin/org/olcbox/app/vpn/desktop/LinuxTunControllerTest.kt`
- Test: существующие Linux controller tests в `sharedUI/src/jvmTest/kotlin/org/olcbox/app/vpn/desktop/`

**Interfaces:**
- Consumes: Task 2 `isRoomUp`, `stop`, `scriptPath` и `deleteRuntime`.
- Produces: `DesktopOlcRtcBond.Room(..., isAlive: () -> Boolean = { process.isAlive }, restart: (() -> Process)? = null)`; `DesktopOlcRtcBond(..., stopSupervisor: (() -> Unit)? = null, runtimeCleanup: () -> Unit = {})`.
- Produces: `LinuxTunController(..., supervisorNaming: () -> String? = { null })`, включающий supervisor в `enginesLeft` и cleanup.

- [ ] **Step 1: Написать failing tests**

В `DesktopOlcRtcBondTest`:

- `linuxRoomUsesStateAndNeverRestartsFromKotlin`: `isAlive=false` убирает комнату из `activeIndexes`; `recover` с `restart=null` не вызывает launcher.
- `sharedSupervisorStopsExactlyOnce`: счётчик общего callback равен 1, per-room `Process.destroy` не вызывается, `runtimeCleanup` вызывается из `deleteConfigs` один раз.
- Windows tests из текущего набора продолжают требовать per-room restart/stop.

В новом `LinuxTunControllerTest`:

- `supervisorNameIsIncludedInCleanupAndLeftoverDetection`: script/cmdline содержит supervisor path и config prefix; `withEnginesEnded` применяется к обоим именам.

- [ ] **Step 2: Подтвердить RED**

Run: `./gradlew :sharedUI:jvmTest --tests org.olcbox.app.vpn.desktop.DesktopOlcRtcBondTest --tests org.olcbox.app.vpn.desktop.LinuxTunControllerTest`
Expected: FAIL: новых параметров и supervisor cleanup нет.

- [ ] **Step 3: Обобщить существующие компоненты без второго пула**

`activeIndexes` и `recover` используют `Room.isAlive`; null restart означает внешний перезапуск. `stop` вызывает `stopSupervisor` один раз либо старый per-room путь. `deleteConfigs` удаляет конфиги и затем `runtimeCleanup`.

В `LinuxTunController` добавить optional `supervisorNaming`; cleanup применяет `withEnginesEnded` последовательно к config prefix и supervisor path. `enginesLeft` возвращает true, если найден любой из них. Старый конструктор остаётся совместимым через default.

- [ ] **Step 4: Подтвердить GREEN**

Run: `./gradlew :sharedUI:jvmTest --tests org.olcbox.app.vpn.desktop.DesktopOlcRtcBondTest --tests org.olcbox.app.vpn.desktop.LinuxTunControllerTest --tests org.olcbox.app.vpn.desktop.LinuxKillSwitchTest`
Expected: PASS, старые Windows/Linux controller tests не изменены по смыслу.

- [ ] **Step 5: Commit**

```bash
git add sharedUI/src/jvmMain/kotlin/org/olcbox/app/vpn/desktop/DesktopOlcRtcBond.kt sharedUI/src/jvmMain/kotlin/org/olcbox/app/vpn/desktop/LinuxTunController.kt sharedUI/src/jvmTest/kotlin/org/olcbox/app/vpn/desktop
git commit -m "feat: supervise linux room lifecycle"
```

### Task 4: Интеграция Linux 4x в DesktopVpnManager

**Files:**
- Modify: `sharedUI/src/jvmMain/kotlin/org/olcbox/app/vpn/DesktopVpnManager.kt`
- Test: `sharedUI/src/jvmTest/kotlin/org/olcbox/app/vpn/desktop/DesktopBondIntegrationTest.kt`

**Interfaces:**
- Consumes: Tasks 1–3, существующие `startDesktopBond`, `recoverDesktopBondRooms`, `rebalanceDesktopBond`, Xray/sing-box и room-state parsers.
- Produces: `isBondedDesktopTun(location, mode)` для Linux/Windows TUN; `linuxBondSupervisor` только на время живого/незавершённого пула.

- [ ] **Step 1: Написать failing integration tests**

- `bondedPoolIsForMarkedLinuxAndWindowsTunProfiles`: true для Linux/Windows bonded, false для System Proxy и plain.
- `linuxPoolUsesOneSupervisorAndSharedCorePath`: исходный `startDesktopBond` создаёт `LinuxBondSupervisor` один раз, не вызывает per-room `LinuxPrivilege.command`; Xray/sing-box и log/state parsers остаются общими.
- `stopSupervisorPrecedesTunCleanup`: в `stopDesktopMode` индекс `stopDesktopBond()` меньше индекса `linuxTunController.stop`.
- `zeroReadyRoomsCleansPoolBeforeXray`: до `xrayCore.start` есть ошибка при `!bond.isRunning()`, а Linux catch вызывает supervisor stop и не делает второй privileged start.
- `failedSupervisorStopRemainsAvailableToTunFallback`: после неуспешного `bond.stop()` runtime не удалён, `linuxBondSupervisor` не null и `stopDesktopCores` не скрывает ошибку повторным запуском.
- Существующие active-port/rebalance invariants остаются: пустой список не доходит до Xray, восстановление использует последний working snapshot.

- [ ] **Step 2: Подтвердить RED**

Run: `./gradlew :sharedUI:jvmTest --tests org.olcbox.app.vpn.desktop.DesktopBondIntegrationTest`
Expected: FAIL: предикат Windows-only, supervisor integration отсутствует.

- [ ] **Step 3: Интегрировать supervisor в существующий start/stop**

Переименовать предикат и включить `LinuxTun`. В `startDesktopBond` сохранить общие конфиги, порты, Xray, sing-box и indicators. Для Linux создать уникальный runtime и один supervisor; `Room` использует общий supervisor Process и `isAlive={supervisor.isRoomUp(index)}`, restart=null. Общий `stopSupervisor` и `runtimeCleanup` передаются в bond. `LinuxTunController` получает `supervisorNaming={linuxBondSupervisor?.scriptPath?.toString()}`.

Для каждой комнаты читать её log корутиной с condition-based EOF retry и generation guard; supervisor stdout тоже потребляется. `currentProcess`/generation не пропускает строки старой сессии.

Перед `linuxTunController.stop` вызвать `stopDesktopBond`, а поздний повторный вызов в конце `stopDesktopMode` удалить. При успешном stop очищать runtime и обнулять ссылку; при неуспешном сохранять supervisor для fallback. Catch старта останавливает supervisor при bond=null, удаляет частичные конфиги только после stop и пробрасывает исходную ошибку.

- [ ] **Step 4: Подтвердить GREEN и полный JVM regression**

Run: `./gradlew :sharedUI:jvmTest`
Expected: PASS, включая Windows/Desktop bond, indicators, routing и subscription tests.

- [ ] **Step 5: Commit**

```bash
git add sharedUI/src/jvmMain/kotlin/org/olcbox/app/vpn/DesktopVpnManager.kt sharedUI/src/jvmTest/kotlin/org/olcbox/app/vpn/desktop/DesktopBondIntegrationTest.kt
git commit -m "feat: enable linux four room tunnels"
```

### Task 5: Release gate и Linux AppImage

**Files:**
- No new product files; outputs в `desktopApp/build/` и `~/Загрузки`.

**Interfaces:**
- Consumes: Tasks 1–4, pinned `/home/hukm/olcrtc-release`, sing-box 1.13.14 и Xray 26.7.11.
- Produces: `~/Загрузки/IlyukhaLine-1.0.472-amd64.AppImage`.

- [ ] **Step 1: Запустить полный compile/test gate**

Run: `./gradlew :sharedUI:jvmTest :desktopApp:compileKotlin :androidApp:compileGithubDebugKotlin`
Expected: PASS.

- [ ] **Step 2: Собрать AppImage с pinned cores**

Подготовить `desktopApp/src/main/resources/native/sing-box` через `scripts/build-singbox.sh` и `xray` из pinned архива. Run:

```bash
APPIMAGE_EXTRACT_AND_RUN=1 OLCRTC_REPO=/home/hukm/olcrtc-release APPIMAGETOOL=<verified-appimagetool> ./gradlew --no-daemon -Polcbox.version=1.0.472 :desktopApp:packageReleaseLinuxAppImage --stacktrace
```

Expected: `BUILD SUCCESSFUL` и `IlyukhaLine-1.0.472-amd64.AppImage`.

- [ ] **Step 3: Проверить сам AppImage**

Извлечь готовый образ, проверить `native/hev-socks5-tunnel-linux-amd64`, `native/olcrtc-linux-amd64`, `native/libolcrtc-linux-amd64.so`, `native/sing-box`, `native/xray`; `file` подтверждает x86-64 AppImage. Скопировать в `~/Загрузки`, удалить только созданные временные native-файлы и extraction-каталог.

- [ ] **Step 4: Hygiene и push**

Run: `git diff --check`, `git status --short`, secret scan. Ожидается чистое дерево, отсутствие реальных URL/ключей/паролей. Запушить `mamba-x4-vless`, дождаться PR Checks и Windows CI; новых source changes без commit не оставлять.

### Task 6: Живая проверка Linux и polkit

**Files:**
- No new product files; sanitized evidence в plan workspace.

**Interfaces:**
- Consumes: Task 5 AppImage и существующие Germany/Finland VDS.
- Produces: подтверждённые TCP/UDP, M/V/J indicators, outage/recovery и завершение процессов.

- [ ] **Step 1: Подтвердить polkit workaround**

Проверить активный `polkit-agent-helper.socket`. Выполнить предложенную пользователю команду `sudo systemctl stop polkit-agent-helper.socket`, затем `pkexec /usr/bin/id`. Expected: `uid=0` с обычным паролем. Только после PASS выполнить `sudo systemctl mask polkit-agent-helper.socket` для сохранения workaround после reboot; не менять PAM/пароль.

- [ ] **Step 2: Проверить Mamba 4x и индикаторы**

Запустить AppImage, выбрать Germany Mamba 4x. Expected: один pkexec для пула, Connected, `M1–M4` активны, TCP и UDP видны в свежих server-side Xray logs. Зафиксировать число вызовов polkit именно для supervisor, не приписывать существующие TUN-запросы пулу.

- [ ] **Step 3: Проверить потерю и восстановление m1**

На Germany выполнить `systemctl stop ilyukhaline-olcrtc@m1.service` по SSH и установить EXIT-trap, который гарантированно запустит её обратно через 60 секунд и при прерывании. Текущий `room-outage` не использовать: он принимает только `j1–j4`. Expected: `M1` красный, остальные пути несут трафик, нет нового pkexec; после restart `M1` зелёный, TUN не сброшен. Подтвердить все `m*` active и снять trap.

- [ ] **Step 4: Smoke VK и SaluteJazz**

По очереди подключить VK 4x и SaluteJazz 4x. Expected: V/J indicators, Connected и fresh TCP/UDP. Если сервис/провайдер недоступен, записать конкретную ошибку и не выдавать smoke за PASS.

- [ ] **Step 5: Остановка, изоляция и итог**

Disconnect. Expected: supervisor и движки завершены, TUN удалён, нет постоянных перезапусков; `~/.olcbox` и установленный Ghostlane не изменены. Записать результаты/ограничения в ledger. Не объявлять работу завершённой при красных или невыполненных проверках.
