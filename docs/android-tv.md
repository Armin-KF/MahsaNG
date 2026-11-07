# MahsaNG on Android TV: audit and implementation record

Branch: `tv-redesign`. Base: upstream `5c16783`.

## 1. What the repository is

MahsaNG is a v2rayNG derivative: Activities and Fragments, XML layouts, view binding, RecyclerView,
LiveData and MMKV. There is no Compose. VPN traffic runs through `libv2ray.aar` (Mahsa Xray-core, built with
gomobile) and `libtun2socks.so`, in the `:RunSoLibV2RayDaemon` process. The UI and the service talk through
broadcasts (`MessageUtil`, `AppConfig.MSG_*`).

Entry points before this work: `MainActivity` (phone launcher and `LEANBACK_LAUNCHER`), `UrlSchemeActivity`,
`ScScannerActivity`, `ScSwitchActivity`, `TaskerActivity`, and the widget and QS tile.

### Can the published repository build a working APK?

**Not as published.** The README says the source cannot build the shipped app, and the audit confirmed more
than that: the Kotlin is an older snapshot than the resources, so the tree did not compile at all.

| Problem found | Evidence | What this branch does |
|---|---|---|
| Closed classes are missing | `my_preference_storage`, `GFW_sutil`, `GFW_txt_crypt`, `wget_module`, `config_pkg_reader` | Excludes the classes that need them. None is declared in the manifest. No substitutes were written. |
| Psiphon AAR is missing | `psi.*` | Excludes `ca/psiphon/**` and `PsiphonVpnService` (also not in the manifest) |
| Plugin code needs an unpublished API | `AngApplication.application` | Excludes `plugin/**` |
| Gradle 7.5 with AGP 8.4.2; `-XX:MaxPermSize` | Neither runs | Gradle 8.7; flag removed |
| `rxpermissions:0.9.4` no longer hosted | 404 on Central, JCenter and JitPack | `com.github.tbruyelle:RxPermissions:0.9.3` |
| `libv2ray` API moved on | `initV2Env(path, key)`, `measureDelay(url)`, `measureOutboundDelay(cfg, url)` | Call sites updated |
| 46 resource files reference unpublished resources | Found by a fixed-point scan of references | Moved to `app/src/closed-tree-res/` with `git mv`; not deleted, not built |
| Duplicate and API-35-only resources | `color/ic_*_background`, `values-v35` | Removed. `values-v35` has no effect at targetSdk 33. |
| Kotlin and DTOs out of step | 211 compile errors, 94 in `ServerActivity` | See "Features not in this build" |
| Wrong class in `activity_settings.xml` | `com.v2ray.ang.feature.appSetting…` | Fixed; Settings would have crashed at runtime |

`libv2ray.aar` is git-ignored. Fetch it before building:
`curl -L -o V2rayNG/app/libs/libv2ray.aar https://github.com/GFW-knocker/AndroidLibXrayLite/releases/download/v1.26.10-r2/libv2ray.aar`

Toolchain used: JDK 17, Android SDK 33 and 34, build-tools 34.0.0.
Build with: `./gradlew :app:assembleDevDebug`

### Features not in this build

These need closed code or unpublished protocol models. The shipped MahsaNG app has them; this build does not:

- Mahsa server accounts, encrypted donated configs, EMS, WARP and MASQUE generators, scanners, the update checker
- Psiphon chaining, the in-process TLS/HTTPS fragmentor service (`backServ`), and ADS
- MVLESS, Hysteria2, HTTP and DNSTT-style config types, and the per-config fragment, mux and ECH form fields

So this build is a real, working client for user-supplied VLESS, VMess, Trojan, Shadowsocks, SOCKS, WireGuard and
custom JSON configs and subscriptions. It is not a drop-in replacement for the Play Store app. The maintainers would
need to port `ui/tv` and the resources into their private tree to ship it with those features. The TV code calls
only `MmkvManager`, `AngConfigManager`, `V2RayServiceManager`, `V2rayConfigUtil`, `MessageUtil`, `Utils` and
`MainViewModel`, which keeps that port small.

## 2. Screen inventory

| Screen | On TV | Notes |
|---|---|---|
| Dashboard | **New** `ui/tv/TvDashboardActivity` | Leanback launcher entry. Tiles: Get free configs, Servers, Subscriptions, Add, Test, Settings |
| Servers | **New** `ui/tv/TvServersActivity` | All `menu_main` actions are here. Re-sorts by latency when a test-all run finishes |
| Subscriptions | **New** `ui/tv/TvSubscriptionsActivity` | Replaces `SubSettingActivity` on TV |
| Settings | **New** `ui/tv/TvSettingsActivity` | Rail plus the phone's `SettingsFragment`, via `xml-television/pref_settings.xml`; every key kept. Logs is the last rail item |
| `ServerActivity`, `ServerCustomConfigActivity`, `SubEditActivity`, `RoutingSettingsActivity`, `PerAppProxyActivity`, `UserAssetActivity`, `LogcatActivity`, `ScannerActivity` | Phone layouts on the TV theme | `values-television/styles.xml` plus `BaseActivity.adaptPhoneLayoutToTv` |
| `MainActivity` | Redirects to the dashboard on TV | Notifications, shortcuts and URL schemes all land there |

UI problems in the old TV path: a drawer plus an overflow menu as the only navigation; a FAB as the connect control; no
readable focus state; an app bar walled off from the D-pad; mobile text sizes; light-theme defaults.

## 3. Design system

Everything is in `res/values/tv_tokens.xml` and `tv_styles.xml`: surfaces, accent, text, focus, connection status, type scale,
spacing, corners, control sizes and motion durations. `values-w1200dp` and `values-w1800dp` rescale the same tokens, so a TV
reporting a large dp canvas does not shrink the UI.

Type: Manrope (SIL Open Font License 1.1, `res/font/`), Latin only; Persian uses the system font. Surfaces have a soft
vertical gradient and a hairline edge, and every TV window is lit faintly from the top-start corner (`tv_bg_window`).
The dashboard's `TvStatusOrb` and ambient light take the connection color: grey when idle, an amber spinning arc while busy,
a breathing green halo when connected, and red on error or when the post-connect test fails ("No internet").

Focus rule: a focused custom control inverts to a white fill with dark content and a soft halo. The primary action keeps its
brand color when focused, with a white ring. Framework-built rows (preferences and dialog
lists) cannot invert their text, so they get a white ring on a raised surface.

## 4. Connection state

`TvConnection` is the single source of state on TV. Each state comes from a service broadcast. CONNECTING and DISCONNECTING
only mark a request in flight, and watchdogs end them if the service never answers. A probe on resume corrects a stale state
if the service died. A successful connect runs a real delay test automatically, because the core starts even when the server is dead.

## 5. Networking changes

Kept to what the build required. Protocol and config generation code is untouched apart from DTO fields.

- `V2RayServiceManager`: removed the `backServ` calls (no usable constructor in the published tree); updated core API calls;
  `measureV2rayDelay` now measures twice because `connection_test_available` takes two values and would otherwise throw.
- `V2rayConfig`: added WireGuard fields `preSharedKey`, `keepAlive`, `wnoise*`. Names were checked against `infra/conf/wireguard.go` in GFW-knocker/Xray-core.
- `Utils.getUrlContentWithCustomUserAgent`: added 15-second timeouts. It had none, so a stalled subscription hung forever.
- `ConfigImporter.refreshSubscription`: refuses responses with no share links. `importBatchConfig` removes a subscription's servers before parsing, so a captive portal page used to wipe them.
- `SubscriptionItem`: added optional `lastUpdated` and `lastError`.

### Get free configs

The shipped app's "get config" fetches Mahsa's encrypted pool through closed code, so this build does not have it.
`ConfigImporter.getFreeConfigs` stands in for it: it adds each public list in `R.array.free_config_sources` (`tv_strings.xml`,
`name|url`) as a subscription once, then refreshes them all. The dashboard tile, the dashboard's primary button when there
are no servers, and the phone's old `get_rot_config_button7` all call it.

| Source | Content on 2026-11-07 |
|---|---|
| `mahsanet/MahsaFreeConfig` `mtn/sub_1.txt` | 11 Shadowsocks, 1 VLESS |
| `mahsanet/MahsaFreeConfig` `mci/sub_1.txt` | 2 Hysteria2, which this build cannot parse |
| `MatinGhanbari/v2ray-configs` `subs/sub1.txt` | About 195 VLESS, VMess, Trojan and Shadowsocks |

The shipped app's fallback source, `yebekhe/TelegramV2rayCollector` (`tg_accounts_url`), has been deleted from GitHub.
`mahsanet/MahsaFreeConfig/app/sub.txt` is encrypted and is not used.

### Sorting

`MainViewModel` counts the replies to a "Test all" or "Quick TCP test" run (the test service answers every config it is sent)
and fires `testsFinishedAction` when the last one arrives. The Servers screen then sorts: serverless configs first, then working
servers by latency, then failed and untested ones. `MmkvManager.sortByTestResults` pins configs whose proxy outbound is
tagged `fakeproxy-out` (`TvFormat.SERVERLESS_TAG`).

## 6. Files

New: `ui/ConfigImporter.kt` (import code moved out of `MainActivity` and shared with TV), `ui/tv/*`, `test/.../TvFormatTest.kt`,
`res/**/tv_*`, `ic_tv_*`, `layout/*_tv_*`, `values-television/`, `drawable-television/`, `xml-television/`, `values-w1200dp/`, `values-w1800dp/`.

Moved: 44 unbuildable resource files to `app/src/closed-tree-res/`.

Changed for TV: `AndroidManifest.xml`, `BaseActivity`, `MainActivity`, `SettingsActivity`, `MainViewModel`, `MmkvManager`, `activity_sub_edit.xml`.

Changed for the build: see section 1. `ServerActivity` lost 278 lines of branches for types and fields the published code cannot save.

## 7. Verification

Run on the Android TV API 33 x86 emulator, driven by remote key events only. The tunnel test used a local SOCKS5 relay.

| Area | Result |
|---|---|
| Connect, consent, Disconnect, reconnect | Pass. `tun0` appeared and went away; the system listed a VPN network |
| Traffic through the tunnel | Pass. The relay logged `www.gstatic.com`, `www.google.com`, and `www.googleapis.com` from other apps |
| Consent denied | Pass. Error state with a reason |
| Live server switch while connected | Pass |
| Dead server | Pass. Stays "Connected" with a red test failure and "No response" |
| Subscription add, refresh, failed refresh keeps servers, delete | Pass |
| Server select, test all, sort, row options, delete with focus restore | Pass |
| Filter and Manage dialogs open | Pass; their inner actions other than delete were not all run |
| Settings: all categories, list dialog, per-app screen, Back to rail | Pass |
| D-pad edges on lists, rail and settings | Pass, after fixing several leaks found here |
| 1080p@320, 4K@640, 4K@320, 1080p@240, 720p@213 | Dashboard checked at all; server list at 1080p@320 and 4K@320. Sizes set with `wm size` and `wm density` |
| Get free configs from the dashboard tile | Pass. 30 servers became 124; Servers opened afterwards |
| Auto-sort after Quick TCP test, serverless config pinned first | Pass |
| Settings categories and dashboard tiles by mouse click (`hw.screen=multi-touch`) | Pass |
| Persian RTL | Pass after fixes |
| Unit tests | 6 pass (3 are the new `TvFormatTest`) |
| `assembleDevDebug`, `assembleProdRelease` | Both build |

## 8. Known issues and what was not verified

- **Not tested on a physical TV**, and no real overscan panel. Everything above is emulator only.
- **Phone UI compiles but was not run.** The published phone layout needed repairs just to compile, and no phone image was used.
- **Only SOCKS was tested end to end.** VLESS, VMess, Trojan, Shadowsocks, WireGuard and custom JSON are covered by unchanged upstream code, but were not connected here.
- **YouTube & Twitter (no server)** (Servers → Add configuration) imports Mahsa's public `ServerLess_TLSFrag_Xray_Config.json` as a custom config: direct connections with the TLS handshake fragmented. It connected and passed the test on the emulator, which has no filtering, so whether it gets past a real filter is untested.
- **Get free configs on a first run was not tested.** With no servers the primary button fetches instead of opening Add; only the tile path, with servers already saved, was run. The phone button was not run.
- **All free config sources are on `raw.githubusercontent.com`**, which has been filtered or throttled in Iran at times. If it is unreachable, a new user gets no servers. Whether the fetch goes through the serverless config when it is connected was not checked.
- The public servers are unvetted. Expect many dead ones; run Test all before choosing.
- In Settings, the Logs rail item sits partly below the fold at 1080p. The rail scrolls to it.
- Invalid-config and start-timeout error paths are implemented but were not triggered.
- After repeated force-stops of a running VPN, the app's UID lost network until the emulator rebooted. The cause was not found. It did not recur in normal use, and a user would not normally do this.
- Manrope has no Persian glyphs, so Persian text uses the system font. That is deliberate, but the two scripts do not match in style.
- New strings are translated to English and Persian only. Russian, Vietnamese and Chinese fall back to English for the new text.
- Very fast key repeat can lose a press when a row has not been laid out yet. Holding the key still moves through the list.
- The remaining phone-layout forms are usable but plain. `ServerActivity` is a long scrolling form, and Up must be pressed twice to leave a filled text field, because the first press moves the cursor.
- The `prod` flavor uses the official application ID `com.MahsaNG.EMS` and a debug signature. Do not distribute it as the official app. Use the `dev` flavor (`.dev` suffix) for testing.
