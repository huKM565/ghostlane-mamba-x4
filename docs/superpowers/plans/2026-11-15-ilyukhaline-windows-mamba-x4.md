# ИлюхаLine Windows Mamba x4 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a visually and technically separate ИлюхаLine Android app and a Windows TUN build that both carry VLESS Mux/XUDP through four simultaneous Mamba rooms.

**Architecture:** Keep the completed Android pool unchanged except branding. On Windows, create four ordinary olcRTC command configurations with native UDP and direct routing disabled, supervise all four processes as one bond, and connect the existing TUN to sing-box -> Xray roundRobin -> the four rooms. Force a full transport restart whenever either side of a profile switch is bonded.

**Tech Stack:** Kotlin Multiplatform/JVM, Compose Material 3, Windows olcRTC processes, sing-box 1.13.14, Xray 26.7.11, Gradle, GitHub Actions windows-latest.

**Spec:** `docs/superpowers/specs/2026-11-15-ilyukhaline-windows-mamba-x4-design.md`

## Global Constraints

- Visible brand is `ИлюхаLine`; Android applicationId is `ru.hukm.ilyukhaline`.
- Windows has its own upgradeUuid and `%APPDATA%\ИлюхаLine`; do not share Ghostlane's cache or settings.
- Palette is cyan `22D3EE`, dark cyan `103743`, orange `FF8A65`; layout does not change.
- Bonded profiles have exactly four rooms and four unique loopback ports.
- Bonded olcRTC configurations have no direct rules and `udp.enabled=false`; UDP belongs to VLESS Mux/XUDP.
- Ordinary Mamba, failover and VK Calls keep existing behavior.
- No credentials enter source, tests or workflow logs.
- Windows assets are unsigned test builds unless an existing signing certificate is available.
- Do not uninstall or overwrite an installed Ghostlane.

## Review Focus

- A partially started pool must not survive a failed room; Task 3 tests stop-all and config cleanup.
- A switch involving a bonded profile must not reuse a single-room endpoint; Tasks 3 and 4 pin full restart and endpoint replacement.
- A bonded health check must fail when any one room dies; Task 3 pins all-four health.
- A new Windows install must not inherit Ghostlane's cache lock or subscriptions; Task 1 pins the separate data directory and upgrade identity.
- A retry or stale sing-box must not hold the next attempt's cache; Task 4 pins a unique per-attempt cache path and its cleanup.
- Native olcRTC UDP or direct rules must not bypass VLESS; Task 3 pins both false/absent in every generated room config.

---

### Task 1: Apply ИлюхаLine Identity and Palette

**Files:**
- Modify: `androidApp/build.gradle.kts`, `androidApp/src/main/AndroidManifest.xml`, `androidApp/src/main/res/values/strings.xml`
- Modify: `desktopApp/build.gradle.kts`
- Modify: `sharedUI/src/jvmMain/kotlin/org/olcbox/app/desktop/DesktopPaths.kt`
- Modify: `sharedUI/src/commonMain/kotlin/org/olcbox/app/ui/theme/Color.kt`, `PkPalette.kt`
- Test: `sharedUI/src/jvmTest/kotlin/org/olcbox/app/vpn/AndroidBrandingTest.kt`
- Test: `sharedUI/src/jvmTest/kotlin/org/olcbox/app/desktop/DesktopBrandingTest.kt`
- Test: `sharedUI/src/jvmTest/kotlin/org/olcbox/app/ui/theme/IlyukhaLinePaletteTest.kt`

**Interfaces:**
- Produces: Android applicationId `ru.hukm.ilyukhaline`, Windows data directory `ИлюхаLine`, shared `OlcboxDarkColorScheme` and `PkPalette` with the spec colors.

- [ ] **Step 1: Complete the failing branding tests**

Keep the existing Android test's three assertions. Add desktop tests that read the real Gradle file and call `DesktopPaths.appDataDir()` under a simulated Windows OS/APPDATA, asserting the new package, a fixed upgradeUuid different from `6f0aaf78-dbed-4745-9d95-9e63f10a30de`, and the separate directory. Add palette assertions for primary, primaryContainer, tertiary, accent and accent2.

- [ ] **Step 2: Verify RED**

Run `./gradlew :sharedUI:jvmTest --tests "*BrandingTest" --tests "*IlyukhaLinePaletteTest"`.
Expected: desktop/palette tests fail because those values are still Ghostlane/Olcbox.

- [ ] **Step 3: Implement the identity and palette**

Use the exact values in Global Constraints. Change only Windows' default data directory. Generate one new fixed UUID for the Windows upgradeUuid. Keep internal source packages and import schemes unchanged.

- [ ] **Step 4: Verify GREEN**

Repeat Step 2, then run the full `:sharedUI:jvmTest`.

- [ ] **Step 5: Commit**

`git commit -m "feat: brand ilyukhaline across android and windows"`.

### Task 2: Build and Exercise the Android App

**Files:**
- Create outside source: `/home/hukm/Загрузки/ИлюхаLine-1.0.472-mamba-x4.apk`

**Interfaces:**
- Consumes: Task 1 and the already completed Android bonded implementation.

- [ ] **Step 1: Build the release**

Run the standard local environment with `OLCRTC_REPO=/home/hukm/olcrtc-release` and `./gradlew -Polcbox.version=1.0.472 -Polcbox.versionCode=472 :sharedUI:jvmTest :androidApp:assembleGithubRelease --stacktrace`.

- [ ] **Step 2: Inspect and install**

Use `aapt dump badging` to assert package `ru.hukm.ilyukhaline` and label `ИлюхаLine`; run apksigner/zipalign. Copy to the final path and install with `adb install -r` without uninstalling Ghostlane.

- [ ] **Step 3: Exercise the real profile**

Serve the existing local subscription only through adb reverse/local loopback, import it, and verify four room runtimes, core HTTPS, UDP DNS, Mamba x4 -> VK Calls -> Mamba x4, stop and restart. Inspect screenshots and logs directly. If Discord is not safely testable, leave it explicitly unverified.

- [ ] **Step 4: Commit any fix through RED -> GREEN**

Do not weaken a failing scenario. Commit only a source fix with its regression test; generated APKs remain outside git.

### Task 3: Implement Desktop Bond Configuration and Lifecycle

**Files:**
- Modify: `sharedUI/src/jvmMain/kotlin/org/olcbox/app/vpn/desktop/OlcRtcCommand.kt`
- Create: `sharedUI/src/jvmMain/kotlin/org/olcbox/app/vpn/desktop/DesktopOlcRtcBond.kt`
- Test: `sharedUI/src/jvmTest/kotlin/org/olcbox/app/vpn/desktop/OlcRtcCommandTest.kt`
- Test: `sharedUI/src/jvmTest/kotlin/org/olcbox/app/vpn/desktop/DesktopOlcRtcBondTest.kt`

**Interfaces:**
- Produces: `OlcRtcCommand(..., udpEnabled: Boolean = true)`; `udp.enabled` is false when requested and direct rules remain absent when `directRulesFile=null`.
- Produces: `DesktopOlcRtcBond(rooms: List<Room>)`; `Room` holds `process: Process`, `configPath: Path`, `port: Int`.
- Produces: `isRunning(): Boolean`, `stop(): Unit`, `deleteConfigs(): Unit`, `ports(): List<Int>`.

- [ ] **Step 1: Write failing command and lifecycle tests**

Assert four bonded commands have their ordered room ids/ports, no `route:` and `udp.enabled=false`; ordinary commands retain true. With fake processes and temporary config files, assert all-four health, stop attempts every process after one failure, and every config is deleted.

- [ ] **Step 2: Verify RED**

Run both test classes. Expected: `udpEnabled` and `DesktopOlcRtcBond` are absent.

- [ ] **Step 3: Implement the minimal command flag and pool**

Preserve the first stop error after attempting all processes; deletion is attempted for every config. No manager/UI code in the pool.

- [ ] **Step 4: Verify GREEN and commit**

Run the focused tests and full JVM suite, then `git commit -m "feat: supervise four desktop olcrtc rooms"`.

### Task 4: Wire the Bond into Windows TUN and Switching

**Files:**
- Modify: `sharedUI/src/jvmMain/kotlin/org/olcbox/app/vpn/DesktopVpnManager.kt`
- Test: `sharedUI/src/jvmTest/kotlin/org/olcbox/app/vpn/desktop/DesktopBondIntegrationTest.kt`

**Interfaces:**
- Consumes: `LocationConfig.bondedRooms()`, `OlcRtcCommand(udpEnabled=false)`, `DesktopOlcRtcBond`, `XrayConfig.buildVlessBond`, `SingBoxConfig.buildSocksChain`.
- Produces: a bonded-only Windows startup path and `isBonded(location: LocationConfig): Boolean` helper used to force full restart when current or next profile is bonded.

- [ ] **Step 1: Write failing integration tests**

Using the real source-input test pattern or an extracted planner, assert the bonded branch exists only for Windows TUN plus a bond marker; four commands use distinct ports; Xray starts before sing-box; the TUN endpoint is the sing-box front with matching credentials; health requires every room and both cores; stop clears the pool; switches involving a bond take the full-restart path; each TUN attempt uses a unique cache path and cleanup removes it.

- [ ] **Step 2: Verify RED**

Run `DesktopBondIntegrationTest`. Expected: the Windows bonded path and per-attempt cache handling are absent.

- [ ] **Step 3: Implement startup, health, cleanup and full restart**

Allocate four ports, start/wait/log each process, build the pool, then start Xray and the sing-box front. Point Windows TUN at the front, not a room. Keep `process` null for a pool and make every active-room health check use the pool. Add the bond to disconnected cleanup guards and stop it before deleting configs. Use `windows-tun-cache-<session>-<attempt>.db` for every TUN attempt and delete it after that attempt stops. Ordinary branches keep their existing transport behavior.

- [ ] **Step 4: Verify GREEN and commit**

Run the focused test, full JVM suite and real Xray smoke. Commit as `feat: bond four mamba rooms on windows`.

### Task 5: Package Windows and Publish

**Files:**
- Create: `.github/workflows/ilyukhaline-windows.yml`
- Create outside source: `/home/hukm/Загрузки/ИлюхаLine-1.0.472-windows-amd64.msi`, `.exe` and `-portable.zip`

**Interfaces:**
- Produces: Windows unsigned test artifacts with the ИлюхаLine package name and pinned native cores.

- [ ] **Step 1: Add a focused workflow**

On `workflow_dispatch` and the working branch, use windows-latest, JDK 21, Go 1.26.5 and the olcRTC pin. Build sing-box with the existing script, download Xray 26.7.11, run JVM tests and the three existing package tasks. Upload EXE/MSI/portable ZIP. Do not require unrelated release secrets or the release gate; do not publish a GitHub release automatically.

- [ ] **Step 2: Validate the workflow and source**

Run actionlint if available, otherwise parse YAML and inspect every referenced script/path. Push the completed branch to the existing real fork.

- [ ] **Step 3: Build and inspect**

Dispatch the workflow, wait for success, download artifacts to `~/Загрузки`, and verify names plus bundled olcRTC/sing-box/Xray. Record that Windows real-device execution remains unverified until run on Windows.

- [ ] **Step 4: Final review**

Self-review the full diff if no subagent is authorized. Fix Critical/Important findings through RED -> GREEN, record deferred minors, and report exactly which Android/Windows scenarios were and were not exercised.
