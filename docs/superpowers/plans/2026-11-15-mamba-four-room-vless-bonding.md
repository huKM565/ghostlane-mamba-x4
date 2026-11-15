# Mamba Four-Room VLESS Bonding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build Ghostlane 1.0.472 for Android with one subscription profile that carries VLESS with `xudp` through four simultaneous Mamba rooms while leaving VK Calls as a separate working profile.

**Architecture:** Add an explicit `##bond-vless` subscription marker to the existing olcRTC location model. For marked locations, Android starts four independent gomobile runtimes and a sing-box core whose four VLESS outbounds detour through those runtimes and join one round-robin `loadbalance` outbound. The existing Android TUN and bridge connect only to sing-box; unmarked olcRTC and VLESS locations keep their current paths.

**Tech Stack:** Kotlin Multiplatform, kotlinx.serialization, Android `VpnService`, gomobile `mobile.Runtime`, sing-box 1.13.14, VLESS `xudp`, Gradle, JUnit/kotlin.test.

**Spec:** `docs/superpowers/specs/2026-11-15-mamba-four-room-vless-bonding-design.md`

## Global Constraints

- Base source is Ghostlane 1.0.472; APK version is `1.0.472`, version code `472`.
- A bonded profile has exactly four unique rooms and four independent `mobile.Runtime` instances.
- Every VLESS outbound uses `packet_encoding=xudp` and a room-specific `detour`.
- The final sing-box outbound is `loadbalance` with strategy `round_robin` and tag `out`.
- Rooms, olcRTC keys and VLESS parameters come from the subscription; no real credentials enter source, tests or documentation.
- `##rooms` remains failover unless `##bond-vless` is present.
- VK Calls remains a separate unmodified single-runtime profile.
- Native olcRTC uses release pin `2d4266af3947f4d9940efafb0d5aabb6207f6659`; sing-box is 1.13.14 and Xray is 26.7.11.
- Do not use the modified `/tmp/olcrtc-src` checkout as the release engine source.
- Balancing is per connection; do not claim packet-level bonding or aggregation of one TCP connection.
- All four rooms are required for a successful start. Partial-start and switch cleanup must stop every component.
- Work occurs in `/home/hukm/ghostlane-mamba-x4` on branch `mamba-x4-vless`; commit each completed task locally and publish only after verification.

## Review Focus

- A malformed `##bond-vless` must not silently degrade to one-room or failover mode; Task 1 pins rejection.
- Duplicate rooms or ports must not produce a pool with fewer than four real paths; Tasks 1, 2 and 4 pin uniqueness.
- Failure of room N after earlier rooms started must stop rooms 1..N; Task 3 pins complete cleanup.
- The bonded carrier must not enable native olcRTC UDP or direct rules that bypass VLESS; Task 4 pins empty direct rules and disabled runtime UDP.
- Switching Mamba x4 -> VK Calls -> Mamba x4 must leave no old runtime, core or occupied port; Tasks 3-5 pin lifecycle and device smoke.

---

### Task 1: Persist and Parse the Bonded Profile

**Files:**
- Modify: `sharedUI/src/commonMain/kotlin/org/olcbox/app/data/model/LocationConfig.kt:21-74`
- Modify: `sharedUI/src/commonMain/kotlin/org/olcbox/app/data/datasource/LocationsDatasource.kt:1626-1703`
- Test: `sharedUI/src/commonTest/kotlin/org/olcbox/app/data/model/LocationConfigBondTest.kt`
- Test: `sharedUI/src/commonTest/kotlin/org/olcbox/app/data/datasource/LocationsRepositoryImplTest.kt`

**Interfaces:**
- Produces: `LocationConfig.bondVlessLink: String?`, serialized as `bond_vless`, default `null`.
- Produces: `LocationConfig.bondedRooms(): List<String>` returning the primary plus three normalized extra rooms only when `bondVlessLink != null`, otherwise `emptyList()`.
- Produces: `LocationConfig.isComplete()` requiring a parseable `OutboundSpec.Vless` and exactly four bonded rooms when the marker is present.
- Consumes: existing `LinkParser.parse(raw): OutboundSpec?`, `failoverRoomIds`, and `normalized()` semantics.

- [ ] **Step 1: Write failing model tests**

Create `LocationConfigBondTest` with a valid plain VLESS link using UUID `11111111-1111-1111-1111-111111111111` and a 64-character test olcRTC key. Assert:

- `bondedRooms()` returns `[room-1, room-2, room-3, room-4]` in order.
- Duplicate extras are normalized away and leave the profile incomplete when fewer than four remain.
- Three rooms, five rooms, a malformed link, and a Hysteria2 link in `bondVlessLink` are incomplete.
- A location with `bondVlessLink=null` remains complete with its original room and failover semantics.
- JSON serialization followed by deserialization preserves `bondVlessLink` and all four rooms.
- `LocationEntry.from(...).normalized().location` preserves `bondVlessLink`; the entry stores it under `bond_vless` and passes it in both conversion directions.

- [ ] **Step 2: Write failing subscription tests**

Add repository import tests using the existing fake source/MockEngine pattern. The text fixture contains one Mamba link, `##rooms` with three rooms, one `##bond-vless`, and one separate VK Calls link. Assert two imported locations: the first carries the VLESS link and four bonded rooms; VK has `bondVlessLink=null`.

Add malformed-bond cases and assert that the marked location is rejected rather than imported as a one-room or failover location. Assert the same text without `##bond-vless` retains `failoverRoomIds` and has an empty `bondedRooms()`.

- [ ] **Step 3: Run the tests to verify they fail**

Run:

```bash
./gradlew :sharedUI:jvmTest --tests "org.olcbox.app.data.model.LocationConfigBondTest" --tests "org.olcbox.app.data.datasource.LocationsRepositoryImplTest"
```

Expected: compilation failure because `bondVlessLink` and `bondedRooms()` do not exist.

- [ ] **Step 4: Implement the model and parser**

Add `@SerialName("bond_vless") val bondVlessLink: String? = null` with normalized trimming. Implement `bondedRooms()` and extend olcRTC `isComplete()` validation without changing other kinds.

In `parseOlcRtcText`, copy `bondVlessLink = fields["bond-vless"]` together with `failoverRoomIds`, normalize, and run `isComplete()` after all fields have been attached. Do not fall back to the unmarked location when bonded validation fails.

- [ ] **Step 5: Run the tests to verify they pass**

Run the Step 3 command.

Expected: all new tests and all existing `LocationsRepositoryImplTest` cases pass.

### Task 2: Generate the Four-Path sing-box Configuration

**Files:**
- Modify: `sharedUI/src/commonMain/kotlin/org/olcbox/app/net/SingBoxConfig.kt:66-79,180-194,1044-1075`
- Test: `sharedUI/src/commonTest/kotlin/org/olcbox/app/net/SingBoxConfigTest.kt`
- Test: `sharedUI/src/jvmTest/kotlin/org/olcbox/app/net/SingBoxConfigDumpTest.kt`

**Interfaces:**
- Consumes: `OutboundSpec.Vless`, `Routing`, `SocksLogin`, existing `render`, and existing VLESS serialization.
- Produces:

```kotlin
fun buildVlessBond(
    spec: OutboundSpec.Vless,
    roomPorts: List<Int>,
    roomUsername: String,
    roomPassword: String,
    socksPort: Int = SINGBOX_SOCKS_PORT,
    routing: Routing = Routing.Global,
    verboseLogs: Boolean = false,
    login: SocksLogin? = null,
): String
```

- [ ] **Step 1: Write failing builder tests**

Build with ports `[21081, 21082, 21083, 21084]`, room credentials `room-user`/`room-pass`, and inbound credentials `in-user`/`in-pass`. Parse the resulting JSON and assert:

- `outbounds[0]` is tag `out`, type `loadbalance`, strategy `round_robin`, with VLESS tags 1-4 in order.
- Four VLESS objects reuse the input server, port, UUID and `packet_encoding=xudp`; VLESS N has `detour=socks-room-N`.
- Four SOCKS objects point to `127.0.0.1:2108N`, contain the room credentials, and have unique tags.
- The SOCKS inbound uses the requested port and inbound credentials.
- Existing routing still refers to tag `out`.
- Fewer than four, more than four, or duplicate ports throw `IllegalArgumentException`.

- [ ] **Step 2: Add a real-config dump test**

Add `dumpVlessMambaX4`, parsing a plain VLESS link at `127.0.0.1:10000`, calling `buildVlessBond` with the four test ports, and writing `build/singbox-configs/vless-mamba-x4.json` through the existing `dump` helper.

- [ ] **Step 3: Run the tests to verify they fail**

Run:

```bash
./gradlew :sharedUI:jvmTest --tests "org.olcbox.app.net.SingBoxConfigTest" --tests "org.olcbox.app.net.SingBoxConfigDumpTest"
```

Expected: compilation failure because `buildVlessBond` is absent.

- [ ] **Step 4: Implement the builder**

Reuse `render` for inbound, DNS, routing and logs. Reuse the existing VLESS object builder, replacing its tag and adding `detour`; add tagged SOCKS helpers that include room credentials only when nonblank. Insert `loadbalance` as tag `out` first, then four VLESS and four SOCKS outbounds. Validate exactly four distinct ports before rendering.

- [ ] **Step 5: Run unit tests and validate the real schema**

Run the Step 3 command, then:

```bash
SINGBOX_BIN=/path/to/sing-box-1.13.14 scripts/check-singbox-configs.sh
```

Expected: tests pass and `vless-mamba-x4.json` plus all pre-existing dumped configurations pass `sing-box check`.

### Task 3: Implement a Testable Four-Runtime Lifecycle

**Files:**
- Create: `sharedUI/src/commonMain/kotlin/org/olcbox/app/net/OlcRtcBond.kt`
- Test: `sharedUI/src/commonTest/kotlin/org/olcbox/app/net/OlcRtcBondTest.kt`

**Interfaces:**
- Produces:

```kotlin
interface OlcRtcBondRuntime {
    fun start()
    fun waitReady(timeoutMillis: Int)
    fun stop(timeoutMillis: Int)
    fun isRunning(): Boolean
    fun setDNS(servers: String)
}

class OlcRtcBond(private val runtimes: List<OlcRtcBondRuntime>) {
    fun start(timeoutMillis: Int)
    fun stop(timeoutMillis: Int)
    fun isRunning(): Boolean
    fun setDNS(servers: String)
}
```

- `OlcRtcBond` accepts exactly four runtime adapters.
- `start` starts and waits for each runtime in order; any error triggers `stop` on all four and rethrows the original error.
- `stop` and `setDNS` attempt every runtime even when one throws, then rethrow the first error.
- `isRunning` is true only when all four are running.

- [ ] **Step 1: Write failing lifecycle tests**

Use a fake runtime recording calls and capable of throwing at a selected method. Test:

- construction rejects any count other than four;
- successful start calls `start` and `waitReady` on all four with the supplied timeout;
- failure on runtime 3 calls `stop` on all four and rethrows the runtime-3 error;
- `stop` attempts all four when runtime 2 throws;
- `setDNS("1.1.1.1:53")` reaches all four when one throws;
- `isRunning` becomes false when any one runtime is down.

- [ ] **Step 2: Run the tests to verify they fail**

Run:

```bash
./gradlew :sharedUI:jvmTest --tests "org.olcbox.app.net.OlcRtcBondTest"
```

Expected: compilation failure because the lifecycle types are absent.

- [ ] **Step 3: Implement `OlcRtcBond`**

Implement only the interface contract. Keep configuration and Android types out of this common class. Preserve the first error while still attempting all required cleanup or DNS calls.

- [ ] **Step 4: Run the tests to verify they pass**

Run the Step 2 command.

Expected: all lifecycle tests pass.

### Task 4: Wire the Pool into Android Without Changing VK Calls

**Files:**
- Create: `sharedUI/src/androidMain/kotlin/org/olcbox/app/vpn/service/GomobileOlcRtcBondRuntime.kt`
- Modify: `sharedUI/src/androidMain/kotlin/org/olcbox/app/vpn/service/OlcboxVpnService.kt:258-261,455-472,1212-1229,1245-1355,1398-1517,2028-2054,2346-2351,2421-2433`
- Test: `sharedUI/src/jvmTest/kotlin/org/olcbox/app/vpn/OlcboxVpnServiceNativeRunTest.kt` (extend only if its existing source-input checks cover the touched lifecycle)

**Interfaces:**
- Consumes: `LocationConfig.bondVlessLink`, `LocationConfig.bondedRooms()`, `SingBoxConfig.buildVlessBond`, `OlcRtcBond` and `OlcRtcBondRuntime` from Tasks 1-3.
- Produces: `GomobileOlcRtcBondRuntime(runtime: OlcrtcRuntime) : OlcRtcBondRuntime`.
- Produces: `startCore(..., bondRoomPorts: List<Int>? = null)`; when non-null it reads `location.bondVlessLink` and emits the bonded config.
- Produces: service state for the active `OlcRtcBond` and its ordered room ports.

- [ ] **Step 1: Establish the baseline**

Run:

```bash
./gradlew :sharedUI:jvmTest :androidApp:compileGithubDebugKotlin
```

Expected: existing tests and Android Kotlin compilation pass before service edits. Record any pre-existing failure rather than changing unrelated code.

- [ ] **Step 2: Implement the gomobile adapter and runtime configuration**

Delegate the five `OlcRtcBondRuntime` methods to the generated `OlcrtcRuntime` methods with unchanged names and timeout units.

Parameterize `installMobileCallbacks` and `configureMobileTransport` by the runtime being configured while keeping the current single-runtime calls valid. Every pool runtime receives socket protection, upstream DNS, provider, transport, device ID, key, room, port and the same SOCKS credentials.

For bonded carriers, set direct rules to empty and native runtime UDP to false: UDP belongs to VLESS `xudp`, not the olcRTC datagram lane.

- [ ] **Step 3: Implement bonded startup and sing-box startup**

In `startTransport`, select the bonded branch only for an olcRTC location with nonblank `bondVlessLink`.

Allocate four distinct free loopback ports. Create four fresh runtimes via `Mobile.new_()`, configure them with the ordered `bondedRooms()`, start them through `OlcRtcBond`, then call `startCore(..., bondRoomPorts=ports)`. Set `activeCorePort` to the sing-box inbound so the existing bridge and verifier use VLESS, not an individual room.

If any runtime or core fails, stop the whole pool and core and return failure through the existing error/retry path. Do not start the ordinary single `olcrtc` runtime in this branch.

- [ ] **Step 4: Implement complete cleanup and health/DNS handling**

Update `stopMobile`, `stopMobileAndWait`, `isActiveTransportRunning` and `updateUnderlyingNetwork` so they:

- stop both possible runtime shapes and the core, attempting all cleanup;
- wait for the core and all known room ports to be released;
- report a bonded connection as running only when all four runtimes and sing-box are alive;
- apply new upstream DNS to every active runtime;
- preserve the existing Jitsi stop-settle timestamp when a bonded Jitsi pool stops.

Ensure VK Calls and ordinary Mamba continue through `startMobile`; ordinary VLESS continues through the original `startCore` branch.

- [ ] **Step 5: Run compilation and regression tests**

Run:

```bash
./gradlew :sharedUI:jvmTest :androidApp:compileGithubDebugKotlin
```

Expected: all tests and Android compilation pass. Inspect the changed service paths to confirm no ordinary profile was routed through the bonded builder.

### Task 5: Build, Package and Exercise the Deliverable

**Files:**
- Create outside source: `/home/hukm/Загрузки/Ghostlane-1.0.472-mamba-x4.apk`
- Create outside source: `/home/hukm/Загрузки/Ghostlane-mamba-x4-vk-subscription.txt`
- Modify only local build inputs: `local.properties`, `androidApp/jniLibs/` as required by the existing build; no credential files in source documentation.

**Interfaces:**
- Consumes: completed Tasks 1-4, original Ghostlane 1.0.472 release APK, engine pin `2d4266af3947f4d9940efafb0d5aabb6207f6659`.
- Produces: signed full-source APK and a local subscription containing the existing four Mamba rooms, their existing VLESS link, and the separate VK Calls link.

- [ ] **Step 1: Verify and stage original native inputs**

Download or reuse the original `Ghostlane-1.0.472-android-release.apk` and verify SHA-256:

```text
6f1059e47c4b80da07d96a15e4002523b1d8ada7f77c078500f8c63a9c1279ed
```

Stage the original release's `libsingboxcore.so` and `libxraycore.so` for the required ABIs in `androidApp/jniLibs`, preserving their pinned versions. Stage a clean olcRTC source tree at the exact release pin for `OLCRTC_REPO`; do not stage the old patched `libgojni.so`.

- [ ] **Step 2: Run the full test suite and build the APK**

Run with Android SDK/NDK 28.2.13676358 and the clean engine path configured:

```bash
./gradlew -Polcbox.version=1.0.472 -Polcbox.versionCode=472 :sharedUI:jvmTest :androidApp:assembleGithubRelease --stacktrace
```

If the release requires local non-public app configuration, use the project's documented local-only inputs. Do not disable tests or substitute the previous patched APK.

- [ ] **Step 3: Align, sign and inspect the artifact**

Run `zipalign` on the unsigned APK, sign the aligned APK with the existing local signing key where available, then run `zipalign -c -p 4` and `apksigner verify --verbose` on the final artifact. Confirm the required ABIs contain the newly built `libgojni.so` and the pinned sing-box/Xray libraries. Copy it to `/home/hukm/Загрузки/Ghostlane-1.0.472-mamba-x4.apk` and record its SHA-256.

- [ ] **Step 4: Generate the local subscription**

Create `/home/hukm/Загрузки/Ghostlane-mamba-x4-vk-subscription.txt` with one Mamba profile using rooms `0dbe3813` through `0dbe3816`, the existing olcRTC key, `##bond-vless` pointing to the existing plain VLESS endpoint at `127.0.0.1:10000`, and one separate VK Calls link. Keep this credential-bearing file out of source and do not print its contents in logs or the final response.

- [ ] **Step 5: Exercise the real scenario on Android**

With an Android device available through `adb`, install the APK, import the local subscription, and verify:

- one Mamba x4 profile and one VK Calls profile are visible;
- connecting Mamba opens four simultaneous olcRTC sessions and four distinct room SOCKS ports;
- the active bridge points to sing-box, not directly to a room;
- TCP traffic exits through the VDS;
- a UDP DNS request and Discord Voice work through VLESS `xudp`;
- parallel connections use multiple Mamba paths;
- Mamba x4 -> VK Calls -> Mamba x4 works without stale processes or occupied ports;
- stop and reconnect also work.

Collect only sanitized logs without keys or UUIDs. If no Android device is available, finish all other checks and explicitly report the device/Discord checks as unverified rather than claiming them.

- [ ] **Step 6: Check the final workspace**

List modified source files and generated deliverables. Remove temporary logs, downloaded build outputs and credential-bearing temporary files that are not required for delivery; keep the APK and local subscription. Report exactly which automated and real-device checks passed and which remain blocked.
