# VK 4x, Telemost 4x and Multi Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add homogeneous 4-room and mixed 12-room olcRTC profiles on Android and Windows, simplify the UI, and deploy both VDS plus their shared subscription through one Linux script.

**Architecture:** Replace the fixed Mamba-only four-room assumptions with an ordered list of independently configured olcRTC paths. Each path gets its own runtime/process and loopback SOCKS port; the existing Xray VLESS balancer and sing-box front remain the single tunnel. One managed set of twelve server-side rooms per VDS feeds one loopback VLESS Xray inbound; the Multi profile uses all twelve rooms.

**Tech Stack:** Kotlin Multiplatform, Compose, Android gomobile, Windows JVM processes, Go 1.26+, Xray 26.7.11, sing-box 1.13.14, Bash, Python unittest, systemd, SSH.

**Spec:** `docs/superpowers/specs/2026-11-16-vk-telemost-multi-bonding-design.md`

## Global Constraints

- Profiles per VDS: Mamba 4x, VK 4x, Телемост 4x and one Multi 12x; groups are exactly `Германия` and `Финляндия`, with eight profiles in total.
- Start from the first ready path, retain the tunnel while at least one path remains, and never change the TUN-facing port during room recovery.
- Mamba uses DataChannel; VK and Телемост use VP8. A single TCP connection stays on one path.
- Existing single olcRTC profiles, failover `##rooms`, old Mamba 4x subscriptions and German router/client room URLs remain compatible.
- Real room URLs and keys never enter Git, APK, Windows packages or command logs. The app update mechanism remains disabled.
- Server changes use only `scripts/deploy-olcrtc-vds.sh`; no compiling on VDS and no changes to unmanaged 3x-ui, Hysteria, Amnezia or existing nginx sites.
- Телемост uses permanent scheduled meetings, never 24-hour temporary links.
- Before changing `/home/hukm/olcrtc-release`, read its `AGENTS.md` and all `docs/-.md`; new Go code gets the required `// ai-generated` tags and race tests.

## Review Focus

- Repeated or malformed subscription headers: reject a broken Multi without dropping previously saved profiles; Task 1 tests refresh and malformed explicit paths.
- A room dying while Xray is being rebuilt: never emit an empty balancer or leave the old TUN endpoint stopped; Tasks 2–5 test zero-active and changed-active-set transitions.
- Occupancy `0`, unknown occupancy and relay presence: never show unknown as zero or subtract the relay twice; Tasks 6–7 pin the conversion.
- Re-running deployment with existing keys and unmanaged services: preserve both and never run two relays for the same room; Tasks 9–11 use fake-SSH idempotence fixtures.
- SSH failure, invalid remote config and rollback: no secret output, partial new service set or damage to the previous deployment; Tasks 9–10 test redaction and rollback.

---

### Task 1: Explicit mixed-room profile model and subscription parsing

**Files:**
- Modify: `sharedUI/src/commonMain/kotlin/org/olcbox/app/data/model/LocationConfig.kt`
- Modify: `sharedUI/src/commonMain/kotlin/org/olcbox/app/data/datasource/LocationsDatasource.kt:1630-1700`
- Test: `sharedUI/src/commonTest/kotlin/org/olcbox/app/data/model/LocationConfigBondTest.kt`
- Test: `sharedUI/src/commonTest/kotlin/org/olcbox/app/data/datasource/LocationsRepositoryImplTest.kt`

**Interfaces:**
- Produces: `@Serializable data class BondRoomConfig(val room: String, val key: String, val provider: String, val transport: String)` with `fun normalized(): BondRoomConfig`.
- Produces: `LocationConfig.bondedRoomConfigs: List<BondRoomConfig>` serialized as `bonded_rooms`, and `fun bondedPaths(): List<BondRoomConfig>`.
- `bondedPaths()` returns explicit paths when present; otherwise it converts the primary plus `failoverRoomIds` using the primary provider/key/transport for legacy 4x.
- Produces: repeated `##bond-room: <full olcrtc URI>` parsing in subscription order. `LocationEntry` persists the same list through `location`, `from` and `normalized`.

- [ ] **Step 1: Write failing model and import tests**

Add tests proving: legacy Mamba 4x yields four paths; VK and Телемост 4x parse through existing `##rooms`; a Multi with one primary plus eleven `##bond-room` yields four Mamba DataChannel, four VK VP8 and four Телемост VP8 paths; `LocationEntry.from(...).location` retains all twelve; refresh retains the prior saved list when the new response is malformed.

```kotlin
assertEquals(List(4) { "jitsi" } + List(4) { "vkcalls" } + List(4) { "telemost" }, paths.map { it.provider })
assertEquals(List(4) { "datachannel" } + List(8) { "vp8channel" }, paths.map { it.transport })
```

- [ ] **Step 2: Run the focused tests and confirm missing-model failure**

Run: `./gradlew :sharedUI:jvmTest --tests 'org.olcbox.app.data.model.LocationConfigBondTest' --tests 'org.olcbox.app.data.datasource.LocationsRepositoryImplTest'`
Expected: FAIL because `BondRoomConfig` and repeated-header storage do not exist.

- [ ] **Step 3: Implement the model and repeated-header parser**

Add the exact interfaces above. Keep the existing single-value field map for current headers and add a per-location `MutableList<BondRoomConfig>` for `bond-room`; parse each full URI through the existing `parseOlcRtcUri`, never split a URI on commas. Validate 4 or 12 unique provider+room paths, nonblank keys, compatible transports and a valid plain TCP VLESS bond. A malformed refreshed entry is omitted while the repository's existing preservation logic keeps its previous entry.

- [ ] **Step 4: Run all model/repository tests**

Run: `./gradlew :sharedUI:jvmTest --tests 'org.olcbox.app.data.*'`
Expected: PASS, including old failover and Mamba 4x tests.

- [ ] **Step 5: Commit**

```bash
git add sharedUI/src/commonMain/kotlin/org/olcbox/app/data/model/LocationConfig.kt sharedUI/src/commonMain/kotlin/org/olcbox/app/data/datasource/LocationsDatasource.kt sharedUI/src/commonTest
git commit -m "feat: parse mixed olcrtc room pools"
```

### Task 2: Xray balancing for one through twelve active paths

**Files:**
- Modify: `sharedUI/src/commonMain/kotlin/org/olcbox/app/net/XrayConfig.kt:63-168`
- Test: `sharedUI/src/commonTest/kotlin/org/olcbox/app/net/XrayConfigBondTest.kt`
- Test: `sharedUI/src/jvmTest/kotlin/org/olcbox/app/net/XrayConfigDumpTest.kt`

**Interfaces:**
- Consumes: `LocationConfig.bondVlessLink` from Task 1.
- Produces: existing `XrayConfig.buildVlessBond(...)`, now requiring `roomPorts.size in 1..12` and retaining all existing Mux/XUDP values.

- [ ] **Step 1: Write failing twelve-port and invalid-input tests**

Assert twelve VLESS plus twelve SOCKS outbounds, selectors `vless-room-1` through `vless-room-12`, and the exact twelve input ports. Keep tests rejecting empty, duplicate and thirteen-port lists.

- [ ] **Step 2: Verify the twelve-port test fails**

Run: `./gradlew :sharedUI:jvmTest --tests 'org.olcbox.app.net.XrayConfigBondTest'`
Expected: FAIL on the current `1..4` requirement.

- [ ] **Step 3: Raise only the validated upper bound and update stale text**

Use `1..12`; preserve output format and existing tags. Update the error to `between one and twelve distinct room ports`. Add a twelve-path dump fixture for real Xray validation.

- [ ] **Step 4: Run unit and pinned-core checks**

Run: `./gradlew :sharedUI:jvmTest --tests 'org.olcbox.app.net.XrayConfig*' && ./scripts/check-xray-configs.sh`
Expected: PASS; Xray 26.7.11 accepts every emitted config.

- [ ] **Step 5: Commit**

```bash
git add sharedUI/src/commonMain/kotlin/org/olcbox/app/net/XrayConfig.kt sharedUI/src/commonTest/kotlin/org/olcbox/app/net/XrayConfigBondTest.kt sharedUI/src/jvmTest/kotlin/org/olcbox/app/net/XrayConfigDumpTest.kt
git commit -m "feat: balance twelve vless room paths"
```

### Task 3: Generic four/twelve-runtime bond lifecycle

**Files:**
- Modify: `sharedUI/src/commonMain/kotlin/org/olcbox/app/net/OlcRtcBond.kt`
- Test: `sharedUI/src/commonTest/kotlin/org/olcbox/app/net/OlcRtcBondTest.kt`

**Interfaces:**
- Produces: `OlcRtcBond(runtimes: List<OlcRtcBondRuntime>)` accepting exactly 4 or 12 runtimes; existing `start`, `recover`, `activeIndexes`, `requestOccupancy`, `stop`, `needsFullRecovery`, `isRunning`, `setDNS` signatures remain unchanged.

- [ ] **Step 1: Write failing size and recovery tests**

Assert sizes 3 and 5 fail, 4 and 12 succeed. For twelve runtimes, prove all start, the first ready one returns immediately, later recovery returns all twelve, eleven stopped runtimes still leave the bond running, and the last stopped runtime makes `needsFullRecovery()` true.

- [ ] **Step 2: Verify new lifecycle tests fail**

Run: `./gradlew :sharedUI:jvmTest --tests 'org.olcbox.app.net.OlcRtcBondTest'`
Expected: FAIL on the fixed four-runtime requirement.

- [ ] **Step 3: Replace only fixed-size assumptions**

Require `runtimes.size == 4 || runtimes.size == 12`; retain ordered indices and existing cleanup semantics. Do not add provider logic to this generic class.

- [ ] **Step 4: Run lifecycle tests**

Run: `./gradlew :sharedUI:jvmTest --tests 'org.olcbox.app.net.OlcRtcBondTest'`
Expected: PASS for four and twelve paths, stop, DNS and occupancy replay.

- [ ] **Step 5: Commit**

```bash
git add sharedUI/src/commonMain/kotlin/org/olcbox/app/net/OlcRtcBond.kt sharedUI/src/commonTest/kotlin/org/olcbox/app/net/OlcRtcBondTest.kt
git commit -m "feat: support twelve-runtime olcrtc bonds"
```

### Task 4: Android mixed-provider pools

**Files:**
- Modify: `sharedUI/src/androidMain/kotlin/org/olcbox/app/vpn/service/OlcboxVpnService.kt:1218-1555` and bond recovery helpers
- Modify: `sharedUI/src/androidMain/kotlin/org/olcbox/app/vpn/service/GomobileOlcRtcBondRuntime.kt`
- Test: `sharedUI/src/jvmTest/kotlin/org/olcbox/app/vpn/OlcboxVpnServiceNativeRunTest.kt`
- Create: `sharedUI/src/commonMain/kotlin/org/olcbox/app/net/BondPathPlanner.kt`
- Test: `sharedUI/src/commonTest/kotlin/org/olcbox/app/net/BondPathPlannerTest.kt`

**Interfaces:**
- Consumes: `LocationConfig.bondedPaths()` and 1..12-port Xray builder.
- Produces: `data class BondPathPlan(val paths: List<BondRoomConfig>, val ports: List<Int>)`; `fun planBondPaths(config: LocationConfig, allocatePort: () -> Int): BondPathPlan` with equal path/port counts and unique ports.
- Android continues to expose the same active index lists to recovery until Task 7 replaces UI status storage.

- [ ] **Step 1: Write failing planner and service regression tests**

Plan a mixed twelve-path profile and assert all twelve room/provider/key assignments and unique ports. Assert failures return `false` only when no path can start, cleanup covers twelve runtimes, occupancy replay follows every activation, and rebalance keeps the existing front port.

- [ ] **Step 2: Run focused tests before implementation**

Run: `./gradlew :sharedUI:jvmTest --tests 'org.olcbox.app.net.BondPathPlannerTest' --tests 'org.olcbox.app.vpn.OlcboxVpnServiceNativeRunTest'`
Expected: FAIL because mixed-path planning is absent.

- [ ] **Step 3: Implement per-path Android configuration**

Replace `bondedRooms()` and fixed four-port loops with `planBondPaths`. For each index set that path's provider, transport, room and key before start; remove shared `lastMobileProvider` assumptions for pool members. Use generic `olcRTC bond x${activeRooms.size}/${paths.size}` logs without room URLs or keys. Keep all existing network binding, core ordering and recovery.

- [ ] **Step 4: Run Android/JVM verification**

Run: `./gradlew :sharedUI:jvmTest :androidApp:compileDebugKotlin`
Expected: PASS and successful Android compilation.

- [ ] **Step 5: Commit**

```bash
git add sharedUI/src/commonMain/kotlin/org/olcbox/app/net/BondPathPlanner.kt sharedUI/src/commonTest/kotlin/org/olcbox/app/net/BondPathPlannerTest.kt sharedUI/src/androidMain/kotlin/org/olcbox/app/vpn/service sharedUI/src/jvmTest/kotlin/org/olcbox/app/vpn/OlcboxVpnServiceNativeRunTest.kt
git commit -m "feat: run mixed room pools on android"
```

### Task 5: Windows mixed-provider pools

**Files:**
- Modify: `sharedUI/src/jvmMain/kotlin/org/olcbox/app/vpn/desktop/DesktopOlcRtcBond.kt`
- Modify: `sharedUI/src/jvmMain/kotlin/org/olcbox/app/vpn/DesktopVpnManager.kt:1715-1905`
- Test: `sharedUI/src/jvmTest/kotlin/org/olcbox/app/vpn/desktop/DesktopOlcRtcBondTest.kt`
- Test: `sharedUI/src/jvmTest/kotlin/org/olcbox/app/vpn/desktop/DesktopBondIntegrationTest.kt`

**Interfaces:**
- Consumes: `planBondPaths` from Task 4.
- Produces: `DesktopOlcRtcBond` accepting exactly 4 or 12 rooms; `desktopBondNeedsRefresh(active: List<Int>, balanced: List<Int>, expectedSize: Int)` replaces the hard-coded four-room check.

- [ ] **Step 1: Write failing twelve-process tests**

Assert twelve unique ports survive, a dead process is restarted, active subsets reconfigure Xray, and a zero-active refresh returns false rather than emitting an empty Xray config. Verify each generated desktop command receives its own provider/transport/room/key.

- [ ] **Step 2: Verify fixed-four tests fail for twelve rooms**

Run: `./gradlew :sharedUI:jvmTest --tests 'org.olcbox.app.vpn.desktop.DesktopOlcRtcBondTest' --tests 'org.olcbox.app.vpn.desktop.DesktopBondIntegrationTest'`
Expected: FAIL on size and watcher assumptions.

- [ ] **Step 3: Implement generic Windows room creation and recovery**

Use the ordered paths from `planBondPaths`; allocate distinct process/config paths and preserve `udpEnabled = false`, empty direct rules and per-room SOCKS credentials. Pass `expectedSize = bond.rooms.size` to watcher decisions. Keep the same Xray and sing-box front ports during rebalance.

- [ ] **Step 4: Run desktop tests and compilation**

Run: `./gradlew :sharedUI:jvmTest --tests 'org.olcbox.app.vpn.desktop.*' :desktopApp:compileKotlin`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add sharedUI/src/jvmMain/kotlin/org/olcbox/app/vpn/desktop/DesktopOlcRtcBond.kt sharedUI/src/jvmMain/kotlin/org/olcbox/app/vpn/DesktopVpnManager.kt sharedUI/src/jvmTest/kotlin/org/olcbox/app/vpn/desktop
git commit -m "feat: run mixed room pools on windows"
```

### Task 6: Explicit olcRTC room state events

**Files:**
- Modify: `/home/hukm/olcrtc-release/internal/client/client.go`
- Modify: `/home/hukm/olcrtc-release/internal/client/link.go:76-127` and handshake failure paths
- Modify: `/home/hukm/olcrtc-release/mobile/listener.go`
- Modify: `/home/hukm/olcrtc-release/mobile/runtime.go`
- Test: `/home/hukm/olcrtc-release/internal/client/session_open_test.go`
- Test: `/home/hukm/olcrtc-release/mobile/listener_test.go`
- Modify: `scripts/patches/olcrtc-room-occupancy.patch` to include occupancy plus the new room-state patch against the pinned commit

**Interfaces:**
- Produces: Go `type RoomState string` with `RoomJoined`, `RoomJoinFailed`, `RelayReady`, `RelayFailed`; `client.Config.OnRoomState func(RoomState)` and mobile `RoomStateListener.OnRoomStateChanged(room string, state string)`.
- `RelayReady` is emitted beside the existing established-session notification, never when only the SOCKS listener opens. `RoomJoined` follows successful `link.Connect`; join and relay failures are distinct events.
- Desktop emits scrubbed structured lines `client: room state=<state>` without keys or room URLs.

- [ ] **Step 1: Read engine guidance and write failing state tests**

Read all required olcRTC docs. Add tests where a fake provider succeeds joining but never answers handshake: expected states `joined`, then `relay-failed`; a provider connect error gives `join-failed`; established session gives `joined`, then `relay-ready`. Add mobile replay tests for the latest state after listener activation.

- [ ] **Step 2: Verify state tests fail**

Run from `/home/hukm/olcrtc-release`: `go test -race ./internal/client ./mobile -count=1`
Expected: FAIL because room-state API is absent.

- [ ] **Step 3: Implement minimal generic client events**

Call room-joined only after `link.Connect` succeeds, join-failed on its error, relay-failed on handshake failure after a joined room, and relay-ready from both established-session paths. Cache and replay only the current mobile generation. Tag generated Go code as required; do not change wire protocol or provider implementations.

- [ ] **Step 4: Run engine verification and regenerate the CI patch**

Run: `go test -race ./internal/client ./mobile -count=1`. Install the project-documented Mage tool if it is missing, then run the standard `mage check && mage build && mage lint` plus `go test -race ./...`.
Regenerate `scripts/patches/olcrtc-room-occupancy.patch` from the pinned olcRTC diff and verify it applies to a clean checkout.
Expected: all checks PASS and clean-checkout patch application succeeds; do not substitute partial Go checks for a failed Mage gate.

- [ ] **Step 5: Commit engine patch and app-side patch separately**

Commit olcRTC with a short lowercase English conventional message. In the app repo:

```bash
git add scripts/patches/olcrtc-room-occupancy.patch
git commit -m "feat: report olcrtc room connection states"
```

### Task 7: Dynamic room indicators, honest errors and occupancy

**Files:**
- Modify: `sharedUI/src/commonMain/kotlin/org/olcbox/app/vpn/VpnManager.kt`
- Modify: `sharedUI/src/androidMain/kotlin/org/olcbox/app/vpn/service/OlcboxVpnState.kt`
- Modify: `sharedUI/src/androidMain/kotlin/org/olcbox/app/vpn/AndroidVpnManager.kt`
- Modify: `sharedUI/src/jvmMain/kotlin/org/olcbox/app/vpn/DesktopVpnManager.kt`
- Modify: `sharedUI/src/commonMain/kotlin/org/olcbox/app/ui/components/kit/PkBoard.kt:240-457`
- Modify: `sharedUI/src/commonMain/kotlin/org/olcbox/app/ui/features/home/HomeScreen.kt`, `HomeScreenModel.kt`, `HomeScreenContent.kt`, `components/LocationSelection.kt`
- Test: `sharedUI/src/jvmTest/kotlin/org/olcbox/app/ui/render/BondRoomIndicatorsTest.kt`
- Create: `sharedUI/src/commonTest/kotlin/org/olcbox/app/vpn/BondRoomStatusTest.kt`

**Interfaces:**
- Produces: `enum class BondRoomError { CLIENT, SERVER }`.
- Produces: `data class BondRoomStatus(val label: String, val active: Boolean, val occupants: Int? = null, val error: BondRoomError? = null)`.
- Produces: `VpnManager.bondRoomStates: StateFlow<List<BondRoomStatus>?>`; it replaces the two parallel fixed-size flows.
- Produces: `fun displayedOccupants(raw: Int?, relayReady: Boolean): Int?` returning null for unknown, otherwise `max(raw - if (relayReady) 1 else 0, 0)`.
- Labels are `M1..M4`, `V1..V4`, `T1..T4` according to each profile's ordered paths.

- [ ] **Step 1: Write failing status conversion and rendering tests**

Cover raw null -> null, raw 0 -> 0, active Mamba raw 2 + relay -> 1, raw 1 + relay -> 0, absent relay -> no subtraction, and VK/Телемост without provider occupancy -> `—`. Render twelve labelled indicators and both exact Russian errors `Подключение не удалось` and `Сервер не смог подключиться`; states survive a later occupancy event.

- [ ] **Step 2: Verify status tests fail**

Run: `./gradlew :sharedUI:jvmTest --tests 'org.olcbox.app.vpn.BondRoomStatusTest' --tests 'org.olcbox.app.ui.render.BondRoomIndicatorsTest'`
Expected: FAIL because the status model is absent.

- [ ] **Step 3: Implement status state and event wiring**

On start create one status per path. Map Task 6 join/relay events to `CLIENT` or `SERVER` without timeout guesses. Active balancer state and relay state remain separate fields internally so a stale occupancy packet cannot reactivate a dead room. Desktop parses only the new structured state lines. Request cached occupancy/state after every activation and when the UI returns.

- [ ] **Step 4: Run status and lifecycle tests**

Run: `./gradlew :sharedUI:jvmTest --tests 'org.olcbox.app.ui.render.BondRoomIndicatorsTest' --tests 'org.olcbox.app.vpn.*' --tests 'org.olcbox.app.net.OlcRtcBondTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add sharedUI/src
git commit -m "feat: show mixed room health and occupancy"
```

### Task 8: Remove unused home-screen UI and update Android branding

**Files:**
- Modify: `sharedUI/src/commonMain/kotlin/org/olcbox/app/ui/features/home/components/LocationSelection.kt`
- Modify: `sharedUI/src/commonMain/kotlin/org/olcbox/app/ui/features/home/HomeScreenContent.kt`
- Modify: `sharedUI/src/commonMain/kotlin/org/olcbox/app/ui/features/home/RoomDetailPane.kt`
- Modify: `sharedUI/src/commonMain/kotlin/org/olcbox/app/ui/components/kit/PkBoard.kt`
- Modify: `sharedUI/src/commonMain/composeResources/values/strings.xml`, `values-ru/strings.xml` and other localized `notification_title_*` values that still expose Ghostlane
- Modify: `androidApp/src/main/res/mipmap-*/ic_launcher*.png`, `mipmap-anydpi-v26/ic_launcher.xml` and `playstore_icon.png`
- Test: `sharedUI/src/commonTest/kotlin/org/olcbox/app/ui/components/kit/PkBoardModelTest.kt`
- Test: `sharedUI/src/jvmTest/kotlin/org/olcbox/app/ui/render/BondRoomIndicatorsTest.kt`
- Test: `androidApp/src/androidTest/kotlin/org/olcbox/app/AndroidBrandingTest.kt`
- Test: existing home-screen render/model tests

**Interfaces:**
- Consumes: `bondRoomStates` from Task 7.
- Preserves: profile selection, connect control, add/update subscription, grouping, pin/remove controls and basic settings.
- Android VPN notification titles start with `ИлюхаLine`; the launcher label remains `ИлюхаLine`.
- Every legacy and adaptive launcher variant shows one plain, legible Cyrillic `И` instead of the Ghostlane logo. Do not alter the existing applicationId or settings storage for this visual change.

- [ ] **Step 1: Write failing UI-absence and branding tests**

With eight profiles across `Германия` and `Финляндия`, assert both groups and all profiles remain while transport chips, ping values/buttons, seats, history graph, plan/quota bar, provider site and support controls have no semantics nodes. Assert selected Multi still shows its twelve states. Add an installed-APK test that resolves the launcher label and VPN notification resources, requires `ИлюхаLine`, and rejects `Ghostlane`; check that all referenced launcher icon resources exist.

- [ ] **Step 2: Verify the old UI and branding fail the tests**

Run: `./gradlew :sharedUI:jvmTest --tests '*Home*' --tests '*PkBoard*' --tests '*BondRoomIndicatorsTest'`
Expected: FAIL on removed controls still being present.
Run the new instrumentation test on the connected Android device with the existing APK.
Expected: FAIL on the old notification title and/or icon resources.

- [ ] **Step 3: Remove obsolete UI and apply Android branding**

Stop rendering filters, ping, seats/history, plan/quota and provider links. Remove parameters made unused by those blocks and update comments that describe the old cards. Keep subscription refresh/remove and all non-UI probe functionality out of scope rather than deleting working engine code.
Replace every `notification_title_*` user-visible app name with `ИлюхаLine` while retaining status text such as proxy/connected. Redraw launcher foreground, legacy icons and Play Store artwork as a simple large `И`, keeping the existing ИлюхаLine background color and no other logo or lettering. Generate each required density from one master rather than stretching a small bitmap; inspect legacy and adaptive icons at launcher size and confirm the letter is centered, uncropped and readable.

- [ ] **Step 4: Run UI, branding and build verification**

Run: `./gradlew :sharedUI:jvmTest :androidApp:assembleDebug :androidApp:assembleGithubRelease :desktopApp:compileKotlin`
Install the debug APK on the Android device, run `:androidApp:connectedDebugAndroidTest`, open the notification shade during a VPN connection, and inspect the actual launcher icon and notification title.
Expected: JVM and instrumentation tests PASS, both targets compile, the shade shows `ИлюхаLine`, and the icon is only the letter `И`.

- [ ] **Step 5: Commit**

```bash
git add sharedUI/src androidApp/src
git commit -m "feat: simplify ui and update android branding"
```

### Task 9: Deployment script configuration, preflight and offline harness

**Files:**
- Create: `scripts/deploy-olcrtc-vds.sh`
- Create: `scripts/deploy-olcrtc-vds.example.conf`
- Create: `scripts/test_deploy_olcrtc_vds.py`
- Modify: `.github/workflows/pr-checks.yml:419-432`

**Interfaces:**
- Produces CLI: `deploy-olcrtc-vds.sh --config FILE check|build|deploy|subscription|publish|status`.
- Config supplies SSH target, twelve room URLs, per-room keys, VLESS UUID/listen port, local artifact directory and HTTPS publish path. Example contains only made-up values.
- Produces internal `load_config`, `ssh_run`, `check_remote`, `redact` functions; all remote execution goes through `ssh_run`.

- [ ] **Step 1: Write failing offline preflight tests**

Use Python `unittest`, temporary directories and a fake `ssh`/`scp` on PATH. Test missing config fields, four URLs per provider, permanent Телемост URLs, duplicate provider+room rejection, non-loopback VLESS rejection, secret redaction, and `check` issuing only read-only remote commands. Include an SSH failure and assert no key appears in stderr.

- [ ] **Step 2: Verify the harness fails before the script exists**

Run: `python3 scripts/test_deploy_olcrtc_vds.py`
Expected: FAIL with missing script/CLI.

- [ ] **Step 3: Implement config validation and check**

Use Bash `set -euo pipefail`; require explicit `--config`, root SSH and supported commands. `check` reports OS, RAM, disk, ports, existing `ilyukhaline-*`, existing olcRTC/Xray and unmanaged VPN state without changing anything. Never echo config values for keys or UUIDs.

- [ ] **Step 4: Run static and offline checks; wire CI**

Run: `bash -n scripts/deploy-olcrtc-vds.sh && shellcheck -x scripts/deploy-olcrtc-vds.sh && python3 scripts/test_deploy_olcrtc_vds.py`
Expected: PASS. Add this Python test and the new script to the existing Gate scripts CI step.

- [ ] **Step 5: Commit**

```bash
git add scripts/deploy-olcrtc-vds.sh scripts/deploy-olcrtc-vds.example.conf scripts/test_deploy_olcrtc_vds.py .github/workflows/pr-checks.yml
git commit -m "feat: add olcrtc vds deployment preflight"
```

### Task 10: Reproducible build, remote deployment, rollback and status

**Files:**
- Modify: `scripts/deploy-olcrtc-vds.sh`
- Modify: `scripts/test_deploy_olcrtc_vds.py`
- Create: `deploy/systemd/ilyukhaline-olcrtc@.service`
- Create: `deploy/systemd/ilyukhaline-xray.service`
- Create: `deploy/xray-vless.json.template`

**Interfaces:**
- Consumes: CLI and validated config from Task 9; pinned olcRTC patch from Task 6.
- `build` produces a static Linux amd64 olcRTC locally and records its SHA256.
- `deploy` creates `/etc/ilyukhaline`, twelve `ilyukhaline-olcrtc@m1..m4/v1..v4/t1..t4` instances and one dedicated loopback Xray.
- `status` reports only service state, provider, room label, loopback port and health, never secrets.

- [ ] **Step 1: Write failing deployment/rollback tests**

Fake SSH filesystem/state and assert: first deploy writes twelve distinct rooms and one Xray; second deploy leaves keys/URLs and unmanaged units byte-identical; an existing German m1/v1 is adopted rather than duplicated; invalid Xray config triggers restoration of previous managed files and units; a failure never restarts unmanaged VPN services.

- [ ] **Step 2: Verify deploy tests fail**

Run: `python3 scripts/test_deploy_olcrtc_vds.py`
Expected: FAIL because build/deploy/status are not implemented.

- [ ] **Step 3: Implement staged deployment**

Build locally from the pinned olcRTC source and patches. Upload to temporary remote paths, validate configs and SHA256, back up managed predecessors, install atomically, then enable/start the managed set. On mandatory failure stop only newly started managed units and restore backups. Reuse the validated German Xray; on Finland select the configured free loopback port. No remote compilation.

- [ ] **Step 4: Run all script checks**

Run: `bash -n scripts/deploy-olcrtc-vds.sh && shellcheck -x scripts/deploy-olcrtc-vds.sh && python3 scripts/test_deploy_olcrtc_vds.py`
Expected: PASS, including idempotence and rollback.

- [ ] **Step 5: Commit**

```bash
git add scripts/deploy-olcrtc-vds.sh scripts/test_deploy_olcrtc_vds.py deploy
git commit -m "feat: deploy olcrtc room infrastructure"
```

### Task 11: Subscription generation and HTTPS publication

**Files:**
- Modify: `scripts/deploy-olcrtc-vds.sh`
- Modify: `scripts/test_deploy_olcrtc_vds.py`
- Create: `deploy/nginx-ilyukhaline-subscription.conf.template`

**Interfaces:**
- `subscription --output FILE` reads actual managed configs for both VDS and writes eight profiles in the Task 1 syntax.
- `publish` uploads only a generated subscription and installs the isolated nginx location at the configured secret path.

- [ ] **Step 1: Write failing subscription snapshot tests**

Using made-up German and Finnish configs, assert exact group/profile order, four paths in homogeneous profiles, twelve in Multi, reused room URLs in Multi, and no unmanaged profile inclusion. Assert publish refuses a missing/unescaped path and enables no directory listing.

- [ ] **Step 2: Verify generation tests fail**

Run: `python3 scripts/test_deploy_olcrtc_vds.py`
Expected: FAIL because subscription/publish are absent.

- [ ] **Step 3: Implement generation and isolated publication**

Generate each URI directly from its managed server config. Use `##rooms` for homogeneous 4x and primary plus eleven `##bond-room` lines for Multi. Write local output with mode 600, upload through a temporary remote file, validate nginx config before reload, and never print the subscription body or URL to CI logs.

- [ ] **Step 4: Run script tests and import the made-up output in app tests**

Run: `python3 scripts/test_deploy_olcrtc_vds.py && ./gradlew :sharedUI:jvmTest --tests 'org.olcbox.app.data.datasource.LocationsRepositoryImplTest'`
Expected: PASS; generated fixture imports as exactly eight profiles.

- [ ] **Step 5: Commit**

```bash
git add scripts/deploy-olcrtc-vds.sh scripts/test_deploy_olcrtc_vds.py deploy/nginx-ilyukhaline-subscription.conf.template
git commit -m "feat: generate multi-vds room subscription"
```

### Task 12: Deploy and verify Germany without breaking existing clients

**Files:**
- No committed secrets or reports; use a local config outside the repository and the Task 9–11 commands.

**Interfaces:**
- Consumes: the complete script and an Android debug build from Task 8.

- [ ] **Step 1: Run read-only German preflight**

Run: `scripts/deploy-olcrtc-vds.sh --config /home/hukm/.config/ilyukhaline/germany.conf check`
Expected: SSH succeeds; current rooms/keys are identified for adoption; resources and loopback ports are sufficient; no files change.

- [ ] **Step 2: Build locally and deploy Germany**

Run: `scripts/deploy-olcrtc-vds.sh --config /home/hukm/.config/ilyukhaline/germany.conf build`, then `deploy`.
Expected: four Mamba, four VK, four permanent Телемост rooms and one dedicated Xray are active; existing m1/v1 URLs and keys remain unchanged; no unmanaged VPN service restarts.

- [ ] **Step 3: Verify old and new German paths**

Run `status`, then import a local German-only generated subscription on a test Android device. For each of four profiles verify external IP and real TCP plus UDP/XUDP traffic. Stop one room at a time and confirm the tunnel remains; verify start with only one available room and later recovery. Confirm the existing router/client profile still passes traffic.

- [ ] **Step 4: Repeat deploy to prove idempotence**

Run the same `deploy` and compare sanitized status plus remote managed config hashes before/after.
Expected: no changed room, key, UUID or unmanaged file; any failed check blocks Finland until fixed.

### Task 13: Deploy Finland and publish the combined subscription

**Files:**
- No committed secrets; Finnish local config remains outside the repository.

**Interfaces:**
- Consumes: the same script version verified on Germany and four permanent Finnish Телемост links.

- [ ] **Step 1: Run Finnish preflight and deploy**

Run: `scripts/deploy-olcrtc-vds.sh --config /home/hukm/.config/ilyukhaline/finland.conf check`, then `build` and `deploy`.
Expected: the same twelve-room layout and dedicated loopback Xray, without touching existing VPN services.

- [ ] **Step 2: Verify all Finnish profiles**

For each of four profiles verify the Finnish external IP, TCP and UDP/XUDP, first-room start, single-room loss and recovery.
Expected: every profile works or the deployment remains unpublished.

- [ ] **Step 3: Generate and publish the combined subscription**

Run `subscription --output` using both actual VDS configs, inspect only sanitized counts, then `publish`.
Expected: one HTTPS URL returns eight profiles, four `Германия` then four `Финляндия`; directory listing is off.

- [ ] **Step 4: Verify import and refresh on real clients**

Import the URL on Android and Windows. Connect each profile, verify room labels/errors/occupancy behavior, then refresh and confirm all Multi paths survive.
Expected: all eight profiles and refresh work end-to-end.

### Task 14: Full release verification and delivery

**Files:**
- Modify if the project release notes require it: `docs/release-notes/pending.md`
- Outputs are local APK/MSI/EXE/ZIP artifacts, not committed binaries.

- [ ] **Step 1: Run the complete local gate**

Run:

```bash
ANDROID_HOME=/home/hukm/Android/Sdk ANDROID_SDK_ROOT=/home/hukm/Android/Sdk OLCRTC_REPO=/home/hukm/olcrtc-release ./gradlew :sharedUI:jvmTest :androidApp:assembleDebug :androidApp:assembleGithubRelease :desktopApp:compileKotlin
bash -n scripts/deploy-olcrtc-vds.sh
shellcheck -x scripts/deploy-olcrtc-vds.sh
python3 scripts/test_deploy_olcrtc_vds.py
./scripts/check-singbox-configs.sh
./scripts/check-xray-configs.sh
```

Expected: every command exits 0.

- [ ] **Step 2: Inspect final diff and repository hygiene**

Run: `git diff --check && git status --short` and scan the diff for real room URLs, keys, UUIDs and subscription URLs.
Expected: no whitespace errors, generated junk or secrets; only intentional source/tests/templates/docs are committed.

- [ ] **Step 3: Push and verify CI**

Push `mamba-x4-vless`; watch PR Checks and `Build IlyukhaLine for Windows` to completion. Do not treat skipped platform-specific jobs or a superseded run as a code failure; inspect concrete failed jobs if any.

- [ ] **Step 4: Deliver verified artifacts**

Install the fresh Android build and repeat a smoke connection through one 4x and one Multi on each VDS. Download successful Windows MSI/EXE/portable ZIP to `~/Загрузки` and verify artifact names. Report the subscription URL privately to the user, not in Git or public CI logs.

- [ ] **Step 5: Commit any final intentional documentation change**

```bash
git add docs/release-notes/pending.md
git commit -m "docs: note multi-provider room profiles"
```

Skip this commit if no release-note change was needed; do not create an empty commit.
