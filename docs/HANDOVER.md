# SuStream — Handover and Remaining Work

**Purpose of this file.** A self-contained brief for another engineer (or another AI assistant) to
continue this build without needing the conversation that produced it. Read sections 1–4 before
writing any code; section 5 is the ordered work queue.

**State at handover:** 104 Kotlin files, ~20,820 lines.

### Current build

A clean `./gradlew clean :app:assembleDebug` was run at the point this document was last updated.

| | |
| --- | --- |
| Result | **BUILD SUCCESSFUL** — 41 tasks, no warnings that matter |
| Artefact | `app/build/outputs/apk/debug/app-debug.apk` — 22 MB |
| Application id | `com.sustream.tv.debug` (the debug suffix — note it when using `adb uninstall`) |
| Version | `1.0.0-debug` (versionCode 1) |
| compileSdk / targetSdk / minSdk | 37 / 37 / 24 |
| TV eligibility | `leanback-launchable-activity` present; `touchscreen`, `leanback`, `camera`, `microphone`, `telephony`, `wifi`, `gamepad`, `location`, `screen.portrait` all declared not-required; `tv_banner` packaged |
| Permissions | `INTERNET`, `ACCESS_NETWORK_STATE`, `WAKE_LOCK`, `POST_NOTIFICATIONS` — nothing else |

Two benign notes from the build log: `stripDebugDebugSymbols` cannot strip
`libandroidx.graphics.path.so` and `libdatastore_shared_counter.so`, so they ship unstripped. That is
normal for a debug build and costs a little size; release builds strip them.

**What it does when installed.** Home → Details → Player and Live TV are navigable end to end.
Films, TV shows, Search, Library and Settings are honest placeholders (see 5.1). With no playlist and
no addon configured, playback shows the *"this is demo data"* error rather than video — correct
behaviour for an app that ships no content, not a defect.

**The APK has been built and packaged, but not run on a device.** Nothing in this document should be
read as a claim about runtime behaviour: focus order, the rail's expand animation, whether TMDB
artwork actually loads, and every Live TV path are unverified. First device run is the obvious next
QA step.

**If you add an `Authorisation` case, the build will fail** in `provenance()` in
`presentation/details/SourcesSheet.kt` until you write the line the user sees. That is deliberate —
it is how a new authorisation basis cannot ship without explaining itself. It has already caught one
addition (`UserAddon`).

---

## 1. What this project is

An Amazon Fire TV / Android TV client, built from two inputs:

- `~/Downloads/prototype.html` — a React/Tailwind phone prototype supplying the visual language.
- `~/Downloads/Structure.xlsx` — sheet `Features`, 58 features across 14 modules. This is the
  requirement baseline.

Full analysis, including the contradictions found in those inputs and how each was resolved, is in
[`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md). **Read its section 1 before making product
decisions** — several obvious-looking "missing features" are deliberate omissions.

### 1.1 User-configured JSON manifest addons

The app can consume addons that expose a JSON manifest over HTTP. This is a **capability, not an
integration**: no addon ships with the build, there is no addon directory, no discovery, no default
URL, and none is recommended. The only addon the app will talk to is one whose URL the user typed in
and explicitly authorised.

Treat it the way you treat M3U support. A transport is neutral; what makes a source lawful is the
user's relationship with the service behind it, which is why every source still carries an
`Authorisation` — here `Authorisation.UserAddon(addonId, addonName)`, which the sources sheet
displays.

**The constraints are load-bearing.** They are what keeps the excluded workflow in
`IMPLEMENTATION_PLAN.md` §1.2 (index scraping → debrid unrestrict) off this transport. Removing any
of them is a product decision, not a refactor:

| Constraint | Effect |
| --- | --- |
| Only stream objects with a direct `url` are modelled | `infoHash` / `fileIdx` are not deserialised at all, so a P2P stream cannot be represented, let alone played |
| `behaviorHints.notWebReady` responses are dropped | That flag is exactly how an addon marks a stream needing a torrent or local-transport client |
| `https://` only, in every build type | Checked before listing and again before resolving |
| Every URL passes `UrlValidator` with `Usage.APP_SERVICE` | Scheme allowlist, SSRF host rules, no HTTPS→HTTP downgrade |
| Reachability probed before a `ResolvedStream` exists | Nothing reaches ExoPlayer that has not answered a ranged GET |
| `StremioAddonPreferences.authorisedByUser` must be true | A URL alone is not enough; the user confirms entitlement first |

**Do not add on top of it:** an addon directory or catalogue browser, a "popular addons" list,
support for `infoHash` / `fileIdx` / magnet / `externalUrl` descriptors, a debrid unrestrict step
applied to addon results, or any addon URL shipped in the build. Each converts a neutral transport
into the §1.2 workflow.

**Honest limitation to keep in the UI copy:** the app cannot verify that what an addon serves is
licensed — no client can. Same position as M3U. What it does instead is refuse the transports
associated with infringement, require an explicit acknowledgement, record why each source is
playable, and name the addon behind every stream.

**Code:** `provider/stremio/StremioAddonApi.kt`, `StremioAddonSourceRepository.kt`,
`Authorisation.UserAddon` in `domain/model/Playback.kt`, `StremioAddonPreferences` in
`domain/model/Preferences.kt`, one entry in the composite in `core/di/AppContainer.kt` (ordered
after IPTV, before demo). Full rationale: `IMPLEMENTATION_PLAN.md` §1.6.

---

## 2. Environment — read this or nothing will build

### 2.1 Gradle is broken on this machine without a workaround

Every Gradle invocation fails with `java.io.IOException: Unable to establish loopback connection`.

**Cause:** Windows AF_UNIX `connect` fails for socket files created anywhere under
`%LOCALAPPDATA%\Temp` (bind succeeds, connect returns `WSAEINVAL`). Since JDK 21,
`Selector.open()` / `sun.nio.ch.IOUtil.makePipe` builds its wakeup socketpair as an AF_UNIX socket in
the **native** temp directory, so `Selector.open()` fails 100% of the time and the Gradle client can
never reach its daemon. Plain TCP loopback and `Pipe.open()` are unaffected, and the Winsock LSP
catalog is clean — `netsh winsock reset` is **not** the fix.

**Required for every build:**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
export TEMP='C:\gradle-tmp' TMP='C:\gradle-tmp'
./gradlew.bat :app:assembleDebug
```

`-Djava.io.tmpdir` alone does **not** work — the failing code reads the Win32 `GetTempPath()`.
Android Studio uses the normal `%TEMP%`, so **IDE builds hit this too** until `TEMP`/`TMP` are set
system-wide. Suspects on this machine: Radmin VPN WFP filter drivers, NordVPN TAP / OpenVPN DCO,
Docker Desktop's Hyper-V networking.

### 2.2 Toolchain

| | |
| --- | --- |
| AGP | 9.3.2 (**built-in Kotlin** — do not apply `kotlin-android`) |
| Kotlin | 2.2.10 |
| Gradle | 9.5.0 · JDK | 25 (Android Studio JBR) |
| compileSdk / targetSdk | 37 · minSdk | 24 |
| Room | 2.8.4 via KSP `2.2.10-2.0.2` |

`android.disallowKotlinSourceSets=false` in `gradle.properties` is **required**: AGP 9's built-in
Kotlin rejects KSP's source-set registration without it. It is AGP's own documented escape hatch and
is commented in place. Remove only when KSP registers through `android.sourceSets`.

### 2.3 The Kotlin metadata ceiling — check before bumping any dependency

A Kotlin 2.2.x compiler can only read class metadata produced up to Kotlin 2.3.0. Three libraries are
**deliberately not on their newest version**:

| Library | Pinned | Why |
| --- | --- | --- |
| `kotlinx-serialization-json` | 1.9.0 | 1.10+ built with Kotlin 2.3.0/2.3.20 |
| `kotlinx-coroutines` | 1.10.2 | 1.11.0 needs stdlib 2.2.20 |
| `coil3` | 3.3.0 | 3.4+ built with Kotlin 2.3.10, 3.5+ with 2.4 |

Before bumping anything, check the `kotlin-stdlib` version in its Gradle module metadata:
`https://repo1.maven.org/maven2/<group-path>/<artifact>/<version>/<artifact>-<version>.module`.
Symptom of getting it wrong: *"Class 'kotlin.Unit' was compiled with an incompatible version of
Kotlin"*. This is documented in `gradle/libs.versions.toml` under `!! METADATA CEILING !!`.

### 2.4 Credentials

`local.properties` (gitignored) holds a working TMDB v4 read token and a TorBox API key. They reach
the app only through `BuildConfig` `DEV_*` fields, which `BuildConfigAppConfig` reads **only when
`BuildConfig.DEBUG`**. Release builds have no embedded credentials.

⚠️ **These keys were pasted into a chat transcript and should be rotated.**

---

## 3. Architecture and conventions

```
presentation  →  domain (use cases + repository interfaces)  ←  data / iptv / provider / playback
```

- **Single `:app` module**, strict package boundaries. `domain/` has no Android imports.
- **Manual DI.** `core/di/AppContainer.kt` is the whole graph, everything `by lazy`. No Hilt — see
  the class KDoc for the reasoning. Add a new view model to the `creators` map in
  `viewModelFactory`; view models needing a runtime argument get their own factory method
  (`detailsViewModelFactory`, `playerViewModelFactory`, `catalogueViewModelFactory`).
- **Repositories never throw.** Everything returns `AppResult<T>` (`Success | Failure(AppError)`).
  `AppError` is a closed taxonomy so every `when` over it is exhaustive — that is what stops
  "unknown error" becoming the app's answer to everything. `errorMessage()` in
  `designsystem/component/StateViews.kt` is the single exhaustive mapping to user-facing copy; adding
  an `AppError` case will fail compilation there until someone writes the message.
- **View models** expose one `StateFlow<XUiState>` and take intents as method calls. Screens use
  `collectAsStateWithLifecycle()`.
- **`Loadable<T>`** (`presentation/common/Loadable.kt`) — `Idle | Loading | Loaded | Empty | Failed`.
  Use it instead of the `isLoading`/`data`/`error` triple.

### 3.1 Conventions that are easy to get wrong

| Rule | Why |
| --- | --- |
| **Secrets are `Secret`, never `String`** (`core/log/Redact.kt`) | `toString()` returns `"Secret(***)"`, so a data class containing one cannot leak it into a log. |
| **Every logged URL goes through `Redact.url()`** | Xtream embeds credentials in *path segments* (`/live/user/pass/123.ts`); a query-parameter filter misses them entirely. |
| **Every URL goes through `UrlValidator`** before a socket opens | Scheme allowlist, SSRF host rules, no HTTPS→HTTP downgrade. `content://` is rejected on purpose so a hostile playlist cannot reach another app's provider — the document picker path bypasses it deliberately. |
| **UK English throughout** — `colours`, `favourite`, `authorised`, `normalise` | Consistency; the product's primary market. |
| **String concatenation over templates** in most code | Existing house style here; match it. |
| **Every user-visible string in `strings.xml`** | Accessibility and localisation. |
| **`Dimens`, never raw dp** in the design system | 960×540 dp TV canvas; see `designsystem/theme/Dimens.kt`. |

### 3.2 TV-specific rules

- **Every screen must claim initial focus** (`Modifier.initialFocus()`). A screen with nothing focused
  swallows every key press.
- **Cards are one focus target**, with one `contentDescription` covering the whole card. Separately
  focusable children mean three D-pad presses to pass one card.
- **Rails need `Dimens.focusBleed` padding** or the focused card's 1.06× scale is clipped.
- **The player's transport buttons are NOT focusable** — D-pad left/right means *scrub*. Read the
  KDoc on `PlayerScreen` before changing anything there.
- **Dialogs use `focusGroup()` + BACK to dismiss**, never a close glyph.
- Three chip states, not two: selected / focused / both.

---

## 4. What is already done

| Area | Files | State |
| --- | --- | --- |
| `core/` | 16 | Result taxonomy, config + secrets, OkHttp stack (redirect limit, size caps, retry/backoff, redaction, concurrency limit), `UrlValidator`, DI, formatters |
| `designsystem/` | 16 | Tokens from the prototype, cards, rails, buttons, chips, inputs, dialogs, state views, hand-drawn icons, focus helpers |
| `domain/` | 13 | All models, 12 repository interfaces |
| `data/tmdb/` | 5 | API, DTOs, mappers, image-URL builder (fetches `/configuration`), repository |
| `data/local/` | 9 | Room 8 tables (schema exported to `app/schemas/`), watchlist, history/resume with write debouncing, favourites, notifications, diagnostics |
| `data/prefs/` | 2 | DataStore settings, `EncryptedSharedPreferences` credential store |
| `data/backend/` | 4 | Gateway contract, working in-memory mock (auth, refresh-token rotation, guest merge, library sync), `AuthRepositoryImpl` with single-flight refresh |
| `data/mock/` | 2 | Offline catalogue mirroring the prototype's titles |
| `iptv/` | 7 | M3U/M3U8 parser, XMLTV parser (DTD disabled — XXE-hardened), Xtream client with M3U fallback, playlist + EPG repositories |
| `provider/` | 9 | Source contract, IPTV-backed adapter, mock adapter, composite, TorBox + mock, the user-configured JSON-manifest addon adapter (§1.1) and its setup-time manifest probe |
| `playback/` | 2 | Media3 `PlayerManager` (tracks, TV-tuned buffering, error mapping), resolution policy |
| `presentation/` | 17 | Nav rail, routes, nav graph, Home, Details, Sources sheet, Player, **Live TV**, `settings/AddonSettingsViewModel` (no screen yet), placeholders. `catalogue/CatalogueViewModel` exists but is an explicit stub with a TODO |
| Entry point | 2 | `SuStreamApplication` (container + notification channels), `MainActivity` (graph provision, nav host, top-level BACK policy) |

**Known behaviour of the current build:** with no IPTV playlist configured, the only sources are demo
ones, and `MockAuthorisedSourceRepository.resolve()` fails deliberately. So the player shows an honest
*"this is demo data, add a playlist"* error rather than video. **This is correct** for an app that
ships no content. Real video arrives when Live TV lands (5.1) and a playlist is added.

---

## 5. Remaining work, in order

Each item is independently shippable and should end with a green
`./gradlew.bat :app:assembleDebug` (with the env vars from 2.1).

### 5.1 Screens — replace the placeholders

Each replaces a `ComingSoonScreen` entry in `presentation/navigation/SuStreamNavGraph.kt`. **The data
layer behind every one of these is complete and compiles**; only the presentation is missing.

| # | Screen | Repositories already available | Notes |
| --- | --- | --- | --- |
| ~~1~~ | ~~**Live TV**~~ | — | **Done.** `presentation/live/` — screen + view model, wired into the nav graph. Verify against the notes that were here: three add-playlist modes, the cleartext acknowledgement before any `http://` URL, and the windowed EPG grid. |
| 2 | **Search** | `TmdbRepository.search()`, `IptvPlaylistRepository.searchChannels()` | Three result groups (films / shows / channels), scope chips. Debounce input ~300 ms — an on-screen keyboard emits a lot of changes. |
| 3 | **Films / TV grids** | `TmdbRepository.browse()`, `CatalogueViewModel` factory in `AppContainer` | Paged grid with genre + year filters. `PagedResult.MAX_PAGE` is 500 (TMDB's cap). ⚠️ `presentation/catalogue/CatalogueViewModel` currently exists **only as a stub with a TODO** — it compiles but loads nothing. Implement it before or alongside the screen. |
| 4 | **Library** | `WatchlistRepository`, `HistoryRepository` | Three tabs: watchlist / continue watching / history. |
| 5 | **Settings + Diagnostics** | `SettingsRepository`, `TorBoxRepository`, `DiagnosticsRepository` | Use `TvSwitchRow` / `TvSelectRow` / `TvActionRow`. Must include TMDB attribution (`R.string.about_tmdb_attribution`), the content policy text, clear-cache and reset-local-data. Diagnostics is read-only and device-local — **not** an admin panel (see 5.6). |
| 5a | **Addon settings panel** — part of Settings | `SettingsRepository` (`StremioAddonPreferences`), `AddonManifestProbe` | **In progress — step 1 of 3 done** (logic). Remaining: the screen itself, a route to reach it, and strings; then a probe unit test. See "5a progress" below. |
| 6 | **Auth / onboarding** | `AuthRepository`, `MockBackendGateway` | Sign in / sign up / continue as guest. Guest is a first-class state. Wire into the nav graph before `HOME` on first run (`settings.onboardingComplete`). |

#### 5a progress — addon settings panel

Being built in three steps so it can be reviewed incrementally.

**Step 1 — logic. Done, compiles, in the APK.**

- `provider/stremio/AddonManifestProbe.kt` — fetches an addon's `manifest.json` and reports back
  what it actually is (`AddonDescriptor`: id, name, types, normalised base URL). Rejects an addon
  that does not advertise the `stream` resource: valid, but useless to this app, and better said at
  setup than discovered as a permanently empty availability panel.
  Transport failures are rewritten into something actionable — on this screen the network is almost
  always fine and the address is almost always the problem.
- `normaliseAddonBaseUrl()` — accepts `https://host`, `https://host/`, `https://host/manifest.json`
  and a config-path variant. **Shared with `StremioAddonSourceRepository`**, which previously had its
  own copy. That mattered: two normalisers would drift, and the symptom would be an addon that tests
  fine at setup and then returns nothing at playback.
- `presentation/settings/AddonSettingsViewModel.kt` — form state, test, save, remove.
- Wired into `AppContainer`: `addonManifestProbe` (on the **same** HTTP client the adapter uses, so
  a probe succeeds under the timeouts and TLS policy playback will apply) and the view model in the
  registry.

**Two gates are structural, not cosmetic — do not soften them in the UI step.**
`AddonSettingsUiState.canSave` requires *both* a successful probe *and*
`authorisedConfirmed`. Editing the URL clears both, so a verified address cannot be swapped for an
unverified one between testing and saving. `authorisedByUser` is what the adapter checks, and it
must never default to true.

**Step 2 — the screen.** `AddonSettingsScreen`: URL field, display name, Test action showing the
addon's own name back, the authorisation confirmation, current status, remove. Needs a route
(`Routes.SETTINGS_ADDONS` is already declared) and a way to reach it — Settings is still a
placeholder, so either make it a minimal real screen with one row, or hang the addon screen off the
rail temporarily. Strings go in `strings.xml` as usual.

**Step 3 — tests.** `AddonManifestProbeTest` and `normaliseAddonBaseUrl` cases; see 5.2.

**Still open, decide before step 2:** `StremioAddonPreferences` holds **one** addon. Supporting
several means changing the preferences model, the DataStore keys and the adapter — cheaper now than
after the UI exists.

### 5.2 Tests — none exist yet

`app/src/test/` and `app/src/androidTest/` are empty. Dependencies are already in the catalogue
(JUnit4, MockK, Turbine, coroutines-test, Robolectric, MockWebServer, Room-testing, Compose UI test).

Highest value first, all pure-JVM:

- `M3uParserTest` — attribute parsing with commas inside quotes, `#EXTGRP`, BOM, missing header,
  malformed lines, the entry/line-length caps, duplicate `tvg-id`.
- `XmltvParserTest` — **DTD/XXE rejection**, time parsing with and without offset, window filtering,
  missing `stop`.
- `UrlValidatorTest` — scheme allowlist, RFC1918/CGNAT/IPv6-ULA rejection, userinfo rejection,
  cleartext acknowledgement, IDN.
- `RedactTest` — Xtream path-segment collapsing, bearer tokens, JWT shapes, `Secret.toString()`.
- `TmdbMapperTest` — vote-count floor, empty `release_date`, `search/multi` filtering, specials
  ordering.
- `PlaybackProgressTest` — resume/complete thresholds, key building.
- `HistoryRepositoryTest` — write debouncing (inject `FixedTimeSource`).
- `CompositeAuthorisedSourceRepositoryTest` — demo sources sort last; one failing adapter does not
  fail the check.
- `AddonManifestProbeTest` + `normaliseAddonBaseUrlTest` — all four URL forms normalise to the same
  base; an addon advertising no `stream` resource is rejected; a non-JSON body becomes
  `ParseFailed` rather than a crash. `MockWebServer`, no Android dependency.
- `StremioAddonSourceRepositoryTest` — **the constraint tests from §1.1, which are the ones that
  matter**: a stream carrying `infoHash` but no `url` yields nothing; `notWebReady: true` is dropped;
  `http://` is rejected in both listing and resolution; nothing is returned when
  `authorisedByUser` is false; every returned source carries `Authorisation.UserAddon`. Use
  `MockWebServer` — these are pure JSON-in, list-out assertions.

Then instrumented tests for the six journeys in the brief (guest browse+search; show → season →
episode; add playlist → open channel; connect provider; play/pause/resume/exit; watchlist+history).

### 5.3 Documentation

Only `IMPLEMENTATION_PLAN.md` and this file exist. Still owed by the brief:

- `API_CONTRACT.md` — endpoints, request/response examples, error format, pagination, auth rules.
  Mirror `data/backend/BackendGateway.kt` and `LibrarySyncGateway.kt`.
- `SECURITY.md` — sections referenced from code comments: §1 secrets, §2 backup exclusions,
  §3 cleartext policy, §4 SSRF and server-side URL fetch rules, §8 data minimisation, §11 takedown
  contacts.
- `TEST_PLAN.md` — mirrors 5.2 plus device matrix.
- `TRACEABILITY.md` — all 58 workbook rows → screen/module/test. Extract rows with the script pattern
  in the session, or read `Structure.xlsx` sheet `Features` (columns A/B/C).
- `DESIGN_SYSTEM.md` — token tables, prototype→TV mapping.
- `ADMIN_BOUNDARY.md` — why the admin panel is not in the APK, and the server-side design.
- `DEFERRED_AND_RESTRICTED.md` — **important**: the legally-restricted list from 1.1, plus deferred
  items (web companion, PIN/maturity gate, WorkManager refresh, push transport, real backend client).
- `README.md` — setup, the §2.1 build workaround, Fire TV install (`adb connect <ip>:5555`),
  release signing, troubleshooting.

### 5.4 `backend-contract/`

Directory exists and is empty. Needs `openapi.yaml` matching `BackendAuthGateway` +
`LibrarySyncGateway`, and a small standalone mock server.

### 5.5 CI

No workflow yet. `.github/workflows/build.yml` running `assembleDebug`, `test`, `lint`. Note CI has no
`local.properties`, so `devConfig()` returns empty and the app falls back to mock data — that is
intended and means CI needs no secrets.

### 5.6 Boundaries that outlive this queue

Two things that are **not** on the work queue because they must not be built. Both are easy to
"helpfully" add later without noticing what changed.

#### Do not turn the addon transport into a discovery workflow

See §1.1 and `IMPLEMENTATION_PLAN.md` §1.6 for the constraint table. In short: no bundled or default
addon, no directory or "popular addons" list, no `infoHash` / `fileIdx` / magnet / `externalUrl`
support, no debrid unrestrict applied to addon results, and `authorisedByUser` never defaults to
true. The adapter is a direct-HTTPS transport for a service the user configured; each of those
additions turns it into the index-scraping workflow §1.2 rules out.

#### Do not build an in-APK admin panel

Workbook rows 54–59 ask for user management, TMDB/provider configuration and system logs. The
prototype puts these in the client, including a field displaying the TMDB API key. Shipping that in a
public APK puts the privileged surface and its credentials on every user's device.

The client's share is `DiagnosticsRepository` — health of *this device's* integrations only, secrets
redacted. The privileged surface belongs in a separate authenticated server API with role checks and
audit logging, specified in `ADMIN_BOUNDARY.md` (5.3).

---

## 6. Useful commands

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"; export TEMP='C:\gradle-tmp' TMP='C:\gradle-tmp'; ./gradlew.bat :app:assembleDebug
```

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"; export TEMP='C:\gradle-tmp' TMP='C:\gradle-tmp'; ./gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest :app:lintDebug
```

```bash
adb connect 192.168.1.50:5555 && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug build installs as `com.sustream.tv.debug`, so uninstall and log filtering use that id:

```bash
adb uninstall com.sustream.tv.debug
```

```bash
adb logcat --pid=$(adb shell pidof -s com.sustream.tv.debug)
```

**Tip used repeatedly during this build:** when unsure of a library's exact API, unzip its AAR from
`~/.gradle/caches/modules-2/files-2.1/` and run `javap -public` on the class. That is how the
`tv-material`, Media3 and Coil signatures used here were confirmed rather than guessed — it is much
faster than compile-and-fix cycles.
