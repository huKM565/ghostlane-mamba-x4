# SaluteJazz 4x Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add automatically provisioned SaluteJazz 4x profiles on Germany and Finland without changing existing Mamba/VK profiles or unmanaged VPN services.

**Architecture:** A small olcRTC CLI provisions reusable `<code>:<password>` rooms through the existing auth provider. A protected local provision file feeds the existing staged VDS deploy and subscription generator; four `j*` services per VDS share the current loopback VLESS/Xray. The app keeps its existing SaluteJazz runtime support and gains `J1–J4` indicators.

**Tech Stack:** Go 1.26+, Mage, golangci-lint v2, Kotlin Multiplatform, Python 3 stdlib, Bash, systemd, Xray 26.7.11, sing-box 1.13.14, Android ADB.

**Spec:** `docs/superpowers/specs/2026-11-18-salutejazz-4x-design.md`

## Global Constraints

- Exactly four SaluteJazz rooms per VDS, provider `salutejazz`, transport `datachannel`, labels `j1–j4` on servers and `J1–J4` in the UI.
- Final subscription: exactly six profiles, Mamba/VK/SaluteJazz for each of Германия and Финляндия; existing Mamba/VK display names remain verbatim.
- No MTS Link, WB Stream, MAX, Телемост, new Multi profiles or Germany↔Finland forwarding.
- SaluteJazz rooms are created automatically through `auth/salutejazz.Provider.CreateRoom`; users create nothing manually.
- Real room references, keys, UUIDs and subscription URLs exist only in protected mode-`600` local files and are redacted from logs; none enter Git.
- `SALUTEJAZZ_ENABLED` defaults to `false`; real protected VDS configs opt in explicitly.
- All server changes use `scripts/deploy-olcrtc-vds.sh`; no compiling on VDS.
- Do not restart unchanged active Mamba/VK/Xray during the additive SaluteJazz deploy; never alter unmanaged 3x-ui, Hysteria, Amnezia or WD TT.
- Preserve existing 4/8/12-path import compatibility and current rebalance/recovery behavior.
- A completely fresh install defaults to `RoutingMode.BypassRussia` («Россия напрямую»); stored bundles, legacy migrations and an explicit saved `Global` choice remain unchanged.
- Before changing `/home/hukm/olcrtc-release`, read its `AGENTS.md` and `docs/-.md`; new Go code gets required `// ai-generated` tags, Mage gates and race tests.
- Live failure stops before full VDS rollout; do not claim unrun TCP/UDP/device checks as successful.
- Routine smoke uses Connected state and server Xray access logs, not repeated ipify requests.

## Review Focus

- A malformed or duplicate saved room reference must not be overwritten or trigger creation of more rooms — Task 2 pins atomic validation/idempotence.
- A SaluteJazz `<code>:<password>` reference is not an HTTPS URL and must still pass config/subscription parsing without leaking its password half — Tasks 3–4 pin validation and redaction.
- Adding `j1–j4` to an already healthy deployment must leave unchanged active Mamba/VK/Xray processes running — Task 3 pins selective restart and rollback.
- A bundle with one to three `j` rooms must fail rather than emit a broken 4x or invalidate previously saved profiles — Tasks 4–5 pin incomplete-group rejection and refresh preservation.
- SaluteJazz has no reliable occupancy feed: an active `J*` room must show `✓`, not zero participants — Task 5 pins labels and unknown occupancy.

---

### Task 1: olcRTC SaluteJazz room provisioner CLI

**Files:**
- Create: `/home/hukm/olcrtc-release/cmd/olcrtc-salutejazz-room/main.go`
- Create: `/home/hukm/olcrtc-release/cmd/olcrtc-salutejazz-room/main_test.go`
- Modify: `scripts/patches/olcrtc-room-occupancy.patch` after the engine commit.

**Interfaces:**
- Consumes: existing `auth/salutejazz.New().CreateRoom(ctx, auth.Config{})`.
- Produces: binary `build/olcrtc-salutejazz-room`; successful stdout is exactly one validated `<code>:<password>` line, errors produce no room output.
- Produces: updated pinned app patch containing the engine's occupancy, exact-egress and room-provisioner commits.

- [ ] **Step 1: Write failing CLI tests**

Test `run(ctx, stdout, createRoom)` with a fake creator returning `abc123:passw0rd`; assert stdout is exactly `abc123:passw0rd\n`. Add cases for creator errors and malformed refs (`ABC123:passw0rd`, `abc123`, `abc123:passw0rd:extra`), asserting a non-nil error and empty stdout.

- [ ] **Step 2: Verify the tests fail**

Run: `go test -race ./cmd/olcrtc-salutejazz-room`
Expected: FAIL because the package does not exist.

- [ ] **Step 3: Implement the minimal command**

Define `run(ctx context.Context, stdout io.Writer, createRoom func(context.Context) (string, error)) error`. Validate with the provider's `<code>:<password>` shape, write only on success, and have `main` call the real provider with a 30-second timeout. Add required `// ai-generated` tags and keep secrets out of errors.

- [ ] **Step 4: Run focused and engine checks**

Run: `go test -race ./cmd/olcrtc-salutejazz-room ./internal/auth/salutejazz && go build -o build/olcrtc-salutejazz-room ./cmd/olcrtc-salutejazz-room && mage check && mage lint`
Expected: PASS, binary exists, lint reports 0 issues.

- [ ] **Step 5: Commit in the engine checkout**

```bash
git -C /home/hukm/olcrtc-release add cmd/olcrtc-salutejazz-room
git -C /home/hukm/olcrtc-release commit -m "feat: add salutejazz room provisioner"
```

- [ ] **Step 6: Regenerate and verify the pinned app patch**

Regenerate `scripts/patches/olcrtc-room-occupancy.patch` from pinned base `2d4266af` through the new engine HEAD. Apply it to a separate clean pinned checkout, run the client/mobile race tests there, and confirm the app build can produce the room-provisioner binary without live network access.

```bash
git add scripts/patches/olcrtc-room-occupancy.patch
git commit -m "feat: pin salutejazz room provisioner"
```

### Task 2: Protected idempotent room-file provisioning

**Files:**
- Create: `deploy/provision_salutejazz.py`
- Create: `scripts/test_provision_salutejazz.py`
- Modify: `scripts/deploy-olcrtc-vds.sh` usage/dispatch only for the `provision-salutejazz` command.

**Interfaces:**
- Consumes: Task 1 binary via `--tool /absolute/path`.
- Produces: `python3 deploy/provision_salutejazz.py --tool PATH --output FILE`, writing `SALUTEJAZZ_URL_1..4` and matching `SALUTEJAZZ_KEY_1..4` shell assignments to a mode-`600` FILE.
- Produces: deploy command `provision-salutejazz --tool PATH --rooms-file FILE`.

- [ ] **Step 1: Write failing provision tests**

Use a fake tool that emits sequential valid refs. Assert first run creates four rooms and four identical keys, output mode is `600`, stdout contains no refs/keys, and second run calls the tool zero times and leaves bytes unchanged. Add malformed existing input, duplicate refs, fake-tool failure and interrupted-write tests; FILE must remain unchanged and no partial values may be printed.

- [ ] **Step 2: Verify RED**

Run: `python3 scripts/test_provision_salutejazz.py`
Expected: FAIL because `deploy/provision_salutejazz.py` is absent.

- [ ] **Step 3: Implement atomic provisioning**

Use stdlib `argparse`, `re`, `secrets`, `subprocess` and `pathlib`. Validate refs with `^[a-z0-9]{4,16}:[a-z0-9]{4,16}$`, keys with 64 hex chars, require unique rooms and one shared key per group. Write a same-directory temporary mode-`600` file, flush/fsync and atomically replace only after all four rooms exist.

- [ ] **Step 4: Wire the deploy subcommand and verify GREEN**

Add `provision-salutejazz` to usage/dispatch, requiring absolute tool/output paths and never logging file contents. Run: `python3 scripts/test_provision_salutejazz.py && bash -n scripts/deploy-olcrtc-vds.sh`.
Expected: all tests PASS.

- [ ] **Step 5: Commit**

```bash
git add deploy/provision_salutejazz.py scripts/test_provision_salutejazz.py scripts/deploy-olcrtc-vds.sh
git commit -m "feat: provision protected salutejazz rooms"
```

### Task 3: Additive VDS deployment for `j1–j4`

**Files:**
- Modify: `scripts/deploy-olcrtc-vds.sh:59-132,156-287`
- Modify: `deploy/remote-deploy.sh:19-28,107-249`
- Test: `scripts/test_deploy_olcrtc_vds.py`
- Modify: `.github/workflows/pr-checks.yml` only if the new Python test is not already included by an existing test glob.

**Interfaces:**
- Consumes: Task 2 room file via `SALUTEJAZZ_ROOMS_FILE=/absolute/path` and `SALUTEJAZZ_ENABLED=true`.
- Produces: staged `j1.yaml..j4.yaml` with provider `salutejazz`, transport `datachannel`, exact existing `egress.allow`; managed systemd services `ilyukhaline-olcrtc@j1..j4.service`.
- Produces: managed bundle sections `###ROOM j1` through `###ROOM j4` and sanitized status rows `J1..J4 salutejazz STATE`.
- Produces: `room-outage --label j1 --seconds 60`, a managed test command that stops exactly one enabled SaluteJazz service and restarts it after the bounded pause or on interruption.

- [ ] **Step 1: Write failing deploy tests**

Add fixtures enabling SaluteJazz through a protected room file. Assert four YAMLs and labels, datachannel, idempotent adoption, redaction, default-off behavior and rejection of partial/duplicate groups. Add an additive-deploy test seeded with active Mamba/VK/Xray: their config bytes stay unchanged and fake systemctl logs contain no restart for those units, while `j1–j4` start. Add room-outage tests rejecting non-`j*` labels and proving interruption still restarts exactly the selected unit.

- [ ] **Step 2: Verify RED**

Run: `python3 scripts/test_deploy_olcrtc_vds.py DeployLifecycleTest SubscriptionTest`
Expected: FAIL on missing SaluteJazz config/render/label support.

- [ ] **Step 3: Implement local validation/rendering**

Source `SALUTEJAZZ_ROOMS_FILE` only when enabled; validate refs using Task 2's shape, shared keys and uniqueness. Map provider `SALUTEJAZZ` directly to `salutejazz`, labels to `j1–j4`, and transport to datachannel. Keep MAMBA/VKCALLS behavior and existing Telemost-removal behavior unchanged. Extend fetch/status to the new labels.

- [ ] **Step 4: Implement selective remote adoption/restart**

Accept `[mvtj][1-4]`. Adopt existing managed `j*` YAML before installation. For previously active units whose staged config and Xray JSON are unchanged, keep them running during this additive deploy; enable/start new `j*` units and restart only units whose managed config changed. Preserve rollback, VK `activating` allowance and Telemost stale cleanup. A dead new `j*` service must trigger rollback. Implement bounded `room-outage` with an EXIT/TERM/INT restart trap and a 1–300-second range check.

- [ ] **Step 5: Verify the complete script gate**

Run: `bash -n scripts/deploy-olcrtc-vds.sh deploy/remote-deploy.sh && shellcheck -x scripts/deploy-olcrtc-vds.sh deploy/remote-deploy.sh && python3 scripts/test_deploy_olcrtc_vds.py && python3 scripts/test_gate_scripts.py`
Expected: PASS with 0 ShellCheck issues.

- [ ] **Step 6: Commit**

```bash
git add scripts/deploy-olcrtc-vds.sh deploy/remote-deploy.sh scripts/test_deploy_olcrtc_vds.py .github/workflows/pr-checks.yml
git commit -m "feat: deploy salutejazz room pools"
```

### Task 4: Six-profile subscription generation

**Files:**
- Modify: `deploy/generate_subscription.py`
- Modify: `deploy/testdata/subscription.expected.txt`
- Test: `scripts/test_deploy_olcrtc_vds.py`
- Test: `scripts/test_provision_salutejazz.py` only for shared fixture helpers, if needed.

**Interfaces:**
- Consumes: Task 3 bundle sections `ROOM j1..j4`.
- Produces: optional `SaluteJazz 4x` profile in `render_group(group, rooms, vless)`, with compact `##rooms` for shared keys and full `##bond-room` links for differing adopted keys.
- Produces: real six-profile output ordered Mamba, VK, SaluteJazz per VDS.

- [ ] **Step 1: Write failing generator tests**

Enable protected SaluteJazz fixtures on both fake VDS. Assert exactly six primary links and exact names/groups, four datachannel paths per SaluteJazz profile, no Multi/Telemost/MTS, mode `600`, and snapshot equality. Add one-to-three-room and differing-key cases; incomplete groups fail, differing keys produce three full bond-room links.

- [ ] **Step 2: Verify RED**

Run: `python3 scripts/test_deploy_olcrtc_vds.py SubscriptionTest`
Expected: FAIL because `j*` bundle/profile support is absent.

- [ ] **Step 3: Implement generator support**

Extend labels and bundle regex with `j`; keep Mamba/VK mandatory and require optional Jitsi-independent SaluteJazz count to be zero or four. Add `("SaluteJazz 4x", "j")` to profiles and update shell profile-count arithmetic to `2 + optional Telemost + optional SaluteJazz`. Do not restore Multi generation.

- [ ] **Step 4: Regenerate the expected fixture and verify GREEN**

Run the focused SubscriptionTest, inspect the sanitized fixture, then run all deploy and gate tests plus ShellCheck.
Expected: six-profile snapshot PASS; no real room refs/keys enter the fixture.

- [ ] **Step 5: Commit**

```bash
git add deploy/generate_subscription.py deploy/testdata/subscription.expected.txt scripts/deploy-olcrtc-vds.sh scripts/test_deploy_olcrtc_vds.py
git commit -m "feat: publish salutejazz four-room profiles"
```

### Task 5: App import and `J1–J4` indicators

**Files:**
- Modify: `sharedUI/src/commonMain/kotlin/org/olcbox/app/vpn/BondRoomStatus.kt`
- Test: `sharedUI/src/commonTest/kotlin/org/olcbox/app/vpn/BondRoomStatusTest.kt`
- Test: `sharedUI/src/commonTest/kotlin/org/olcbox/app/data/model/LocationConfigBondTest.kt`
- Test: `sharedUI/src/commonTest/kotlin/org/olcbox/app/data/datasource/LocationsRepositoryImplTest.kt`
- Test: `sharedUI/src/jvmTest/kotlin/org/olcbox/app/data/datasource/GeneratedSubscriptionImportTest.kt`
- Test: `sharedUI/src/jvmTest/kotlin/org/olcbox/app/ui/render/BondRoomIndicatorsTest.kt`

**Interfaces:**
- Consumes: Task 4 six-profile fixture.
- Produces: `bondRoomLabel(index, "salutejazz") == "J${index % 4 + 1}"`; existing provider labels unchanged.

- [ ] **Step 1: Write failing label/import tests**

Assert four SaluteJazz paths import as datachannel and render `J1–J4`; active unknown occupancy remains `✓`, not zero. Extend the generated fixture assertion to six profiles with three per group. Add malformed-SaluteJazz refresh coverage proving the previous six saved profiles survive.

- [ ] **Step 2: Verify RED for the new behavior**

Run: `./gradlew :sharedUI:jvmTest --tests org.olcbox.app.vpn.BondRoomStatusTest --tests org.olcbox.app.data.datasource.GeneratedSubscriptionImportTest`
Expected: FAIL on current fallback `R*` labels and old fixture count.

- [ ] **Step 3: Implement the minimal app change**

Add `PROVIDER_SALUTEJAZZ -> "J$suffix"` in `bondRoomLabel`. Do not alter provider normalization, transport handling or occupancy arithmetic; those already support SaluteJazz. Update only tests/fixtures required by the new subscription.

- [ ] **Step 4: Run app tests and compilation**

Run: `./gradlew :sharedUI:jvmTest :androidApp:compileGithubDebugKotlin :desktopApp:compileKotlin`
Expected: PASS, including unknown-occupancy checkmark and malformed-refresh preservation.

- [ ] **Step 5: Commit**

```bash
git add sharedUI/src/commonMain/kotlin/org/olcbox/app/vpn/BondRoomStatus.kt sharedUI/src/commonTest/kotlin/org/olcbox/app/vpn/BondRoomStatusTest.kt sharedUI/src/commonTest/kotlin/org/olcbox/app/data/model/LocationConfigBondTest.kt sharedUI/src/commonTest/kotlin/org/olcbox/app/data/datasource/LocationsRepositoryImplTest.kt sharedUI/src/jvmTest/kotlin/org/olcbox/app/data/datasource/GeneratedSubscriptionImportTest.kt sharedUI/src/jvmTest/kotlin/org/olcbox/app/ui/render/BondRoomIndicatorsTest.kt
git commit -m "feat: show salutejazz room indicators"
```

### Task 6: Fresh-install Russia-direct routing

**Files:**
- Modify: `sharedUI/src/commonMain/kotlin/org/olcbox/app/data/datasource/LocationsDatasource.kt:240-260`
- Test: `sharedUI/src/commonTest/kotlin/org/olcbox/app/data/datasource/RoutingSettingsTest.kt`

**Interfaces:**
- Consumes: existing `LocationBundleV4` storage and `RoutingMode.BypassRussia`.
- Produces: `LocationsRepositoryImpl(MemoryLocationsDataSource()).getRoutingSettings().mode == RoutingMode.BypassRussia` only for a completely fresh install; serialized model defaults and stored choices remain unchanged.

- [ ] **Step 1: Write failing fresh-install and preservation tests**

Change the fresh-repository default assertion to `BypassRussia`. Add separate cases proving an existing empty bundle with `Global`, a legacy-location migration, and an explicit user-saved `Global` all remain `Global`; keep the pre-routing serialized bundle assertion `Global`.

- [ ] **Step 2: Verify RED**

Run: `./gradlew :sharedUI:jvmTest --tests org.olcbox.app.data.datasource.RoutingSettingsTest`
Expected: only the completely fresh-install case fails with actual `Global`.

- [ ] **Step 3: Implement repository-only initialization**

In `getBundleUnlocked`, when both stored state and legacy locations are absent, return the fresh bundle with `routing = RoutingSettings(mode = RoutingMode.BypassRussia)`. Do not change `RoutingSettings.mode`'s serialized default, stored non-null bundles or the legacy adoption branch.

- [ ] **Step 4: Verify routing and repository suites**

Run: `./gradlew :sharedUI:jvmTest --tests org.olcbox.app.data.datasource.RoutingSettingsTest --tests org.olcbox.app.data.datasource.LocationsRepositoryImplTest`
Expected: PASS; subsequent repository instances read the saved choice rather than reapplying the fresh default.

- [ ] **Step 5: Commit**

```bash
git add sharedUI/src/commonMain/kotlin/org/olcbox/app/data/datasource/LocationsDatasource.kt sharedUI/src/commonTest/kotlin/org/olcbox/app/data/datasource/RoutingSettingsTest.kt
git commit -m "feat: default new installs to russia direct"
```

### Task 7: Throwaway single-room live gate

**Files:**
- Outputs only under the plan's ignored workspace and `/tmp`; no committed binaries, reports or live room refs.

**Interfaces:**
- Consumes: Task 1 engine source and existing `internal/gate` SaluteJazz room maker.
- Produces: go/no-go evidence for full VDS rollout; sanitized gate reports remain local.

- [ ] **Step 1: Run the real CLI SaluteJazz gate**

Run: `go test -count=1 -timeout 25m ./internal/gate -run '^TestGate$' -v -olcrtc.gate -olcrtc.gate-providers=salutejazz -olcrtc.gate-transports=datachannel -olcrtc.gate-clients=cli -olcrtc.gate-dir=/tmp/ilyukhaline-salutejazz-cli`
Expected: every selected cell passes; report contains no room password or key.

- [ ] **Step 2: Run the lean mobile gate**

Run the same provider/transport with `-tags olcrtc_lean`, `-timeout 45m`, `-olcrtc.gate-clients=mobile` and a separate `/tmp/ilyukhaline-salutejazz-mobile` directory.
Expected: every selected cell passes, including TCP and UDP scenarios.

- [ ] **Step 3: Inspect artifacts and clean up**

Read the sanitized summaries, confirm no secrets, and delete temporary live configs/processes. If either gate fails, stop rollout and report the failing cell/error; do not deploy 4x.

- [ ] **Step 4: Record the go decision in the execution ledger**

Record exact commands, selected cells and results. No product commit is needed for this throwaway verification task.

### Task 8: Real VDS rollout and six-profile publication

**Files:**
- Modify only protected local `/home/hukm/.config/ilyukhaline/` files, never their committed counterparts.
- Outputs: local sanitized status/log summaries in the plan workspace.

**Interfaces:**
- Consumes: Tasks 1–5 and 7 plus explicit `SALUTEJAZZ_ENABLED=true` in both protected VDS configs.
- Produces: four active `j*` services per VDS and six profiles at the existing secret `sub.hukm.dev` URL.

- [ ] **Step 1: Provision protected rooms idempotently**

Build Task 1's tool, run `provision-salutejazz` separately for Germany and Finland, verify mode `600`, four unique rooms per file and no secret stdout. Re-run once and assert unchanged bytes/tool-call count.

- [ ] **Step 2: Run preflight and additive deploy**

Run `check`, `build`, then `deploy` separately through `scripts/deploy-olcrtc-vds.sh`. Expected: Germany/Finland each gain four active SaluteJazz services; existing Mamba/VK/Xray remain active and unchanged services are not restarted. Verify unmanaged services are still active.

- [ ] **Step 3: Verify idempotence and rollback evidence**

Record managed config hashes, repeat deploy, and assert no hash or unwanted service-state changes. Do not induce a destructive live rollback solely for demonstration; rely on Task 3's rollback tests.

- [ ] **Step 4: Generate and publish the combined subscription**

Run `subscription --output` on the protected combined file, assert exact six names and absence of Multi/Telemost/MTS, then `publish --input`. Fetch the HTTPS URL and compare bytes with the local protected file without printing contents.

- [ ] **Step 5: Record sanitized server evidence**

Run `status` on both VDS. Expected: 12 managed olcRTC services plus healthy Xray per VDS; no secret values in the ledger/logs.

### Task 9: Device verification and release delivery

**Files:**
- No new product files; outputs are local APK/MSI/EXE/ZIP and sanitized verification summaries.

**Interfaces:**
- Consumes: Tasks 5–6 app changes and Task 8 published subscription/VDS services.
- Produces: verified fresh-install routing and Android behavior plus current Windows artifacts in `~/Загрузки`.

- [ ] **Step 1: Run the complete local release gate**

Run full JVM tests, Github Debug/Release APKs, desktop compile, bash syntax, ShellCheck, all Python deploy/gate tests, pinned sing-box check/start checks and pinned Xray validation. Expected: every command exits 0.

- [ ] **Step 2: Install the fresh APK and verify first launch/import**

Install with ADB and clear only test-app data for a clean first launch. Before importing, assert «Россия напрямую» is selected; select Global, restart and assert that choice survives. Restore Russia-direct if desired for smoke, then import the existing secret subscription and assert two groups/six profiles with no stale Multi/MTS/Telemost entries.

- [ ] **Step 3: Smoke SaluteJazz 4x on both VDS**

Connect Germany and Finland SaluteJazz profiles separately. Require Connected plus fresh accepted TCP and UDP entries in the corresponding server's Xray log; inspect `J1–J4` indicators and ensure active unknown occupancy shows `✓`. Do not rely on ipify.

- [ ] **Step 4: Verify one-room loss and recovery**

Start Task 3's `room-outage --label j1 --seconds 60` through the managed script, observe the remaining paths carry traffic and the affected indicator turns red, then let the bounded command restore it and observe recovery without a full tunnel reset. Confirm every `j*` service is active before continuing.

- [ ] **Step 5: Inspect hygiene, commit and push remaining source changes**

Run `git diff --check`, secret scans and `git status --short` in both checkouts. Commit only intentional source/tests/docs and push the app's `mamba-x4-vless` branch. Keep the engine commit in its pinned local checkout and app patch; do not push an engine branch without a separately authorized remote/target.

- [ ] **Step 6: Verify CI and download artifacts**

Wait for current PR Checks and Windows workflow; inspect concrete failed jobs rather than treating cancelled superseded runs as code failures. Download `IlyukhaLine-1.0.472-windows-amd64.exe`, `.msi` and `-portable.zip` to `~/Загрузки` and verify file types/names.

- [ ] **Step 7: Report completion honestly**

Report the existing subscription URL privately, six profiles, actual TCP/UDP/device evidence, current service states and any deferred limitations. Do not call the work complete while required checks are red or unrun.
