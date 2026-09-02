# SuStream — Fire TV / Android TV Implementation Plan

**Status:** living document · **Updated:** 2026-09-02 · **Target:** Amazon Fire TV + Android TV / Google TV

This revision supersedes the earlier provider-specific plan. It removes core provider-cloud functionality and makes user-configured HTML/JSON manifest addons the mechanism through which the app obtains direct playable links and URLs.

---

## 1. Product decisions and risks

### 1.1 TV-first product design

The supplied prototype is a phone/web application, while SuStream is a remote-controlled TV application. The TV client uses a left navigation rail, focus states, `LazyRow` rails, full-width dialogs dismissed with BACK, and legible ten-foot UI typography.

| Prototype pattern | TV replacement | Why |
| --- | --- | --- |
| Bottom tab bar | Expandable left navigation rail | Reachable with a D-pad |
| Hover styling | Focus styling | TVs have no pointer cursor |
| Horizontal browser scrolling | `LazyRow` with focus-driven scrolling | D-pad-friendly |
| Small close controls | BACK-dismissible dialogs | Avoids pointer-sized targets |
| Admin portal in client | Excluded from APK | Preserves the security boundary |

The visual language remains: violet brand colours, near-black surfaces, amber Live TV accents, emerald health states, rose live badges, rounded cards, poster-first rails, and hero backdrops with gradient scrims.

### 1.2 Source and playback boundary

The app must not implement torrent indexing, magnet resolution, hash-cache probing, DRM circumvention, geo-restriction evasion, unlicensed content aggregation, or any provider-specific debrid/unrestrict workflow.

**What the app supports:**

- `AuthorisedSourceRepository` remains the domain contract for title availability and source selection.
- `MockAuthorisedSourceRepository` supplies deterministic fixtures for offline development and tests.
- `IptvBackedSourceRepository` matches titles against channels and VOD entries from IPTV playlists the user has supplied.
- `HtmlJsonAddonSourceRepository` queries user-configured HTML/JSON manifest addons and uses direct playable URLs returned by those addons.
- `PlayableSource.authorisation` is non-nullable. A result without provenance cannot be passed to the player.

The client performs no provider-specific cloud-library access, account linking, API-key handling, download-link generation, or unrestrict operation. Addons manage any functionality required to resolve their own links; the app only validates and plays accepted direct URLs.

The UI uses neutral language such as *“Checking configured sources”* and identifies the playlist or addon behind each result. It does not expose torrent, tracker, seed, cached-hash, or unrestrict terminology.

### 1.3 IPTV support

The app supports user-supplied M3U/M3U8 URLs, local M3U files, and Xtream-style playlist credentials. Every network path, parameter name, and authentication convention for the Xtream client is isolated in `XtreamEndpointConfig`, allowing provider-specific differences to be changed in one place.

### 1.4 Admin boundary

The TV APK contains no privileged admin panel or platform-wide credentials. Device-local diagnostics are read-only and redact secrets; any administrative functionality belongs in a separately authenticated server-side application with role checks and audit logs.

### 1.5 User-configured HTML/JSON manifest addons

An addon is a user-configured source integration that exposes an HTML or JSON manifest over HTTPS and returns direct playable links or URLs. Addons are managed from their own top-level **Addons** sidebar section, not from Settings.

This is a capability rather than a bundled integration:

- No addon is bundled, promoted, discovered, or recommended.
- The user enters every addon URL themselves.
- The user must explicitly confirm they are authorised to use each configured service.
- The sources sheet identifies the addon that supplied each playable result.
- The app may support multiple configured addons; each is stored, tested, enabled, disabled, edited, or removed independently.

**Safety and validation constraints:**

| Constraint | Effect |
| --- | --- |
| Direct `url` required | Only direct URLs are represented as playable streams |
| HTTPS-only in release builds | No cleartext addon manifests or playback URLs |
| URL validation before use | Scheme rules, SSRF protections, no HTTPS-to-HTTP downgrade |
| Reachability check before playback | A URL is probed before it becomes a `ResolvedStream` |
| Explicit authorisation acknowledgement | An addon remains disabled until the user confirms entitlement |
| Provenance on every result | `Authorisation.UserAddon(addonId, addonName)` is mandatory |
| No default addon configuration | The app stays neutral and user-directed |

The addon transport does not support magnet links, P2P descriptors, hash identifiers, or an external unrestrict step. The app cannot determine whether a third-party addon is licensed to distribute a particular item; users are responsible for configuring services they are entitled to use.

**Setup flow:**

1. The user opens **Addons** from the navigation rail and chooses **Add addon**.
2. They enter an HTTPS addon URL and an optional display name.
3. `AddonManifestProbe` fetches and validates the manifest, confirms stream capability, and displays the addon identity returned by the service.
4. The user explicitly confirms entitlement.
5. The app stores the addon only after both validation and acknowledgement pass.
6. Editing the URL clears validation and acknowledgement, requiring the addon to be tested and confirmed again.

**Suggested code ownership:**

- `provider/htmljson/HtmlJsonAddonApi.kt` — wire models and manifest parsing
- `provider/htmljson/HtmlJsonAddonSourceRepository.kt` — source adapter
- `provider/htmljson/AddonManifestProbe.kt` — validation and URL normalisation
- `domain/model/Playback.kt` — `Authorisation.UserAddon`
- `domain/model/Preferences.kt` — `HtmlJsonAddonPreferences`
- `presentation/addons/` — addon list, add/edit form, test result, authorisation acknowledgement
- `core/di/AppContainer.kt` — addon adapter instances and composite ordering

---

## 2. Architecture

### 2.1 Choice

**Single Gradle module (`:app`), strict package boundaries, MVVM with unidirectional data flow, and manual dependency injection.**

```text
presentation  →  domain (use cases + repository interfaces)  ←  data / iptv / provider / playback
      ↑                                                                    │
      └──────────────── immutable UiState, one-shot UiEvent ───────────────┘
```

- View models expose one immutable `StateFlow<XUiState>` and accept user intents as methods.
- Domain contains pure Kotlin models, repository interfaces, and use cases, with no Android imports.
- Data, IPTV, addon, and playback layers implement domain interfaces.
- Primary integration seams are `TmdbRepository`, `IptvPlaylistRepository`, `AuthorisedSourceRepository`, `PlaybackRepository`, `AuthRepository`, `WatchlistRepository`, `HistoryRepository`, `SettingsRepository`, and `NotificationRepository`.

### 2.2 Technology choices

| Decision | Rationale |
| --- | --- |
| Compose for TV | Native focus handling and TV-appropriate components |
| Single module | Reduces build configuration risk while package boundaries remain explicit |
| Manual DI | Keeps the dependency graph visible and avoids annotation-processing complexity |
| Room + KSP | Typed local persistence and migrations |
| Retrofit + OkHttp + kotlinx.serialization | Clear service boundaries, controlled HTTP behaviour, strict JSON parsing |
| Media3 | HLS, DASH, progressive playback, track selection, subtitles, and TV player support |
| Navigation Compose | Central route and back-stack policy |
| No WorkManager in v1 | Explicit refresh and refresh-on-open are more predictable for TV devices |

### 2.3 Package layout

```text
app/src/main/java/com/sustream/tv/
├── SuStreamApplication.kt          Application; owns AppContainer
├── MainActivity.kt                 Single activity; hosts nav graph
├── core/
│   ├── config/                     AppConfig and runtime flags
│   ├── di/                         AppContainer and ViewModelFactory
│   ├── net/                        OkHttp, UrlValidator, interceptors, limits
│   ├── result/                     AppResult and AppError taxonomy
│   ├── log/                        Logging and secret redaction
│   └── util/                       Clock, formatters, dispatchers
├── designsystem/
│   ├── theme/                      Colour, type, dimensions, shape
│   ├── component/                  Cards, rail, buttons, chips, dialogs
│   └── focus/                      Focus restoration and D-pad helpers
├── domain/
│   ├── model/                      Media, playback, playlist and addon models
│   ├── repository/                 Repository interfaces
│   └── usecase/                    User-visible operations
├── data/
│   ├── tmdb/                       API, DTOs, mappers, cache, image URL builder
│   ├── local/                      Room database, entities and DAOs
│   ├── prefs/                      DataStore and secure credential storage
│   ├── backend/                    Backend contract and mock implementation
│   └── mock/                       Offline catalogue fixtures
├── iptv/
│   ├── m3u/                        M3U/M3U8 parser
│   ├── xtream/                     Configurable Xtream-style client
│   ├── epg/                        XMLTV parser
│   └── IptvPlaylistRepositoryImpl.kt
├── provider/
│   ├── htmljson/                   Manifest addon client, probe and adapters
│   └── source/                     Authorised source repository implementations
├── playback/                       PlayerManager, tracks and progress reporting
└── presentation/
    ├── navigation/                 Routes, nav graph and back policy
    ├── home/ movies/ tvshows/ details/ search/ library/ iptv/ epg/
    ├── addons/                     Addon management screens and view models
    ├── settings/ diagnostics/ auth/ player/
    └── common/                     Shared UiState plumbing
```

---

## 3. Navigation

```text
                    ┌──────────────────────── MainActivity ────────────────────────┐
                    │ NavRail: Home · Films · TV · Live TV · Addons · Search       │
                    │          Library · Settings                                   │
                    └──────────────────────────────────────────────────────────────┘

  Splash ─┬─▶ First run: Onboarding ─▶ Auth {Sign in | Sign up | Continue as guest} ─┐
          └─▶ Returning user ───────────────────────────────────────────────────────▶ Home

  Home ──┬─▶ Details(movie|tv) ──┬─▶ Sources sheet ─▶ Player
         │                       ├─▶ Seasons ─▶ Episodes ─▶ Sources sheet ─▶ Player
         │                       └─▶ Watchlist / watched toggles
         ├─▶ Films (grid, genre/year filters, paging)
         ├─▶ TV shows (grid, filters, paging)
         ├─▶ Live TV ─┬─▶ Channels ─▶ Player(live)
         │            ├─▶ TV guide ─▶ Player(live)
         │            ├─▶ Favourites
         │            └─▶ Playlists ─┬─▶ Add playlist {M3U URL | M3U file | Xtream}
         │                           └─▶ Playlist detail {edit | refresh | delete}
         ├─▶ Addons ─┬─▶ Configured addon list
         │           ├─▶ Add addon {URL | test | confirm entitlement | save}
         │           └─▶ Addon detail {enable | disable | edit | retest | remove}
         ├─▶ Search (films | shows | channels)
         ├─▶ Library ─┬─▶ Watchlist
         │            ├─▶ Continue watching
         │            └─▶ History
         └─▶ Settings ─┬─▶ Account ├─▶ Playback ├─▶ Subtitles
                       ├─▶ IPTV ├─▶ Diagnostics
                       └─▶ About / attribution / legal / clear data
```

### 3.1 Addons sidebar section

**Addons** is a top-level destination positioned after **Live TV** and before **Search**. It is not a Settings subpage because configuring playback sources is a primary product function.

The root screen lists configured addons with name, enabled state, last successful test, and a non-sensitive health result. It provides one focused **Add addon** action and supports edit, retest, enable/disable, and remove operations for each existing addon.

### 3.2 Back policy

| Context | BACK does |
| --- | --- |
| Player controls visible | Hides controls |
| Player controls hidden | Saves progress and returns to previous screen |
| Player sheet open | Closes that sheet only |
| Any dialog or sources sheet | Closes it and restores focus |
| Addon add/edit/test flow | Returns to the Addons list; unsaved edits require discard confirmation |
| A section root, including Addons | Moves focus to the navigation rail |
| Navigation rail focused away from Home | Goes to Home |
| Navigation rail focused on Home | Requires double press to exit |

---

## 4. Domain models

```kotlin
enum class MediaType { MOVIE, TV }

data class MediaItem(
    val id: MediaId,
    val type: MediaType,
    val title: String,
    val posterPath: String?,
    val backdropPath: String?,
    val releaseYear: Int?,
    val voteAverage: Double?,
    val voteCount: Int,
    val primaryGenre: String?,
)

sealed interface Authorisation {
    data class UserPlaylist(val playlistId: String, val playlistName: String) : Authorisation
    data class UserAddon(val addonId: String, val addonName: String) : Authorisation
    data object Demo : Authorisation
}

data class PlayableSource(
    val id: String,
    val label: String,
    val container: StreamContainer,
    val authorisation: Authorisation,
    val providerName: String,
    val isLive: Boolean,
    val qualityLabel: String?,
)

data class ResolvedStream(
    val source: PlayableSource,
    val uri: String,
    val headers: Map<String, String>,
    val expiresAt: Instant?,
    val subtitleTracks: List<SubtitleTrack>,
)

data class AddonConfiguration(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val manifestName: String?,
    val enabled: Boolean,
    val authorisedByUser: Boolean,
    val lastTestedAt: Instant?,
    val lastTestResult: AddonTestResult?,
)

enum class AddonTestResult { SUCCESS, UNREACHABLE, INVALID_MANIFEST, NO_STREAM_CAPABILITY, REJECTED }
```

`AppResult<T>` is a sealed `Success | Failure(AppError)`. `AppError` includes exhaustive categories for network, timeout, unauthorised, rate-limited, parse, URL-scheme, content-size, expired, unsupported-format, and unknown failures.

---

## 5. API and authentication assumptions

### 5.1 TMDB

TMDB provides metadata only. The client uses its documented metadata, discovery, search, genre, image configuration, and paging endpoints, caches images and configuration defensively, and presents the required attribution. Metadata availability must never be described as playback availability.

### 5.2 Backend

The backend contract covers account authentication, profile data, watchlists, history, progress, and device-safe diagnostics. It does not hold or proxy addon-specific credentials, provider API keys, or provider-cloud-library operations.

Core endpoints remain:

```text
POST /auth/signup
POST /auth/login
POST /auth/refresh
POST /auth/logout
GET|PATCH /profile
GET|PUT|DELETE /watchlist
GET|PUT /history
PUT /progress
GET /health
```

Guest mode remains local-first and issues no account tokens.

### 5.3 HTML/JSON manifest addons

The client communicates only with user-supplied HTTPS addon endpoints. A manifest probe validates the addon identity and stream capability at setup time; the source adapter queries enabled addons for direct playable URLs when a user selects a title or episode.

The app contains no hard-coded addon endpoint, addon catalogue, provider-specific API client, cloud-library API, account-connect endpoint, provider token store, or provider-specific mock. Link resolution is owned by the configured addon, while the app owns URL validation, provenance, reachability testing, and playback.

### 5.4 Local and synchronised state

| Data | Guest | Signed in |
| --- | --- | --- |
| Watchlist, history, progress, favourites | Room, device-only | Room source of truth, synchronised with backend |
| IPTV playlists and credentials | Local encrypted storage, never synced by default | Same, with opt-in metadata sync only if added later |
| Addon configurations | DataStore plus secure storage for any user-entered confidential fields | Device-local by default; not uploaded automatically |
| Settings | DataStore, device-only | Device-only |

---

## 6. Security and privacy

| Risk | Mitigation |
| --- | --- |
| Secrets in the APK | No production secrets in source code or build configuration |
| IPTV credentials | Encrypted storage, redacted logs, no `toString()` leaks |
| Addon URLs and responses | HTTPS-only, strict manifest parsing, response size caps, content-type checks, URL validation, redirect limits |
| SSRF via user input | Blocks loopback, link-local, private, `.local`, and IPv6 ULA hosts unless an explicit LAN feature is enabled |
| Unsafe playback URL | Direct URL validated before listing, resolving, and playback; no scheme downgrade |
| Token leakage | Central log facade redacts authorisation and credential fields |
| Privileged functionality | No in-APK admin panel and no provider-specific credentials |
| Data minimisation | No analytics SDK in v1; diagnostics are local and opt-in for upload |

---

## 7. Legal and compliance boundaries

1. **No unauthorised content discovery.** No torrent or DDL index scraping, magnet or hash resolution, cached-hash probing, bundled playlists, or automated addon discovery.
2. **No circumvention.** No DRM bypass, geo-restriction evasion, or defeat of provider access controls.
3. **User-supplied IPTV only.** The app ships no channel list and does not discover IPTV providers.
4. **User-configured addons only.** Addons are manually configured, identified in the UI, and must return direct HTTPS URLs acceptable to the app’s validation policy.
5. **No core provider integration.** The app does not connect to or manage provider-cloud services. Any resolution performed by an addon remains inside that user-configured addon.
6. **TMDB attribution and terms.** TMDB is a metadata source, not a video provider.
7. **Privacy.** The release process includes data-minimisation checks, an account-deletion path, and a UK/EU data-protection review.

---

## 8. Phased delivery plan

| Phase | Content | Exit criteria |
| --- | --- | --- |
| 0 | Inspect prototype, workbook, repository, and toolchain | Baseline build and this plan |
| 1 | Plan, API contract, security, test, traceability, design, admin-boundary and deferred documentation | Reviewable documentation |
| 2 | Build configuration, TV manifest, dependency graph, secret plumbing | Debug build succeeds |
| 3 | TV design system and focus components | Components render at TV resolution |
| 4 | Navigation, nav rail, static screens, back policy, **Addons destination** | Every section D-pad reachable |
| 5 | TMDB metadata, caching, paging, image URLs, offline fallback | Browse, search and details work online/offline |
| 6 | Local state: Room, DataStore, secure storage, watchlist, history, resume, favourites | Survives process death |
| 7 | IPTV: M3U/M3U8, Xtream-style input, XMLTV, guide, playlist CRUD | User playlist becomes browsable and playable |
| 8 | Authorised source contract, IPTV adapter, HTML/JSON addon adapter, manifest probe, source validation and source sheet | Sources are validated, attributable, and selectable |
| 9 | Media3 playback: controls, tracks, subtitles, resume, errors and lifecycle | Core playback journeys pass |
| 10 | Addons management screen, Settings, diagnostics, attribution and clear-data | Addons can be fully managed from the nav rail |
| 11 | Unit tests, UI tests, lint and CI | `test`, `lint`, `assembleDebug` green |
| 12 | Device QA, final build and honest release report | Tested artefact and known-issues report |

### 8.1 Current delivery state

| Phase | State |
| --- | --- |
| 0–3 | Done: toolchain diagnosis, build configuration, design system |
| 4 | Navigation and rail exist; Addons must become a separate top-level route and rail item |
| 5 | TMDB and offline mock fallback implemented |
| 6 | Room, DataStore, encrypted storage, watchlist, history, resume and favourites implemented |
| 7 | IPTV parser, client, repositories and Live TV screen implemented; device verification outstanding |
| 8 | Source contract, mock and IPTV-backed adapter implemented; replace provider-specific code with the HTML/JSON addon implementation and remove provider-specific code paths |
| 9 | Media3 engine and player screen implemented; hardware verification outstanding |
| 10 | Addon-management logic exists conceptually; build the independent Addons sidebar route and screens |
| 11 | Not started |
| 12 | Not started |

No current completion claim substitutes for device testing. Focus behaviour, artwork loading, addon setup, playback, and Live TV must be verified on target hardware.

---

## 9. Open decisions

| # | Question | Default | Where to change |
| --- | --- | --- | --- |
| 1 | Is the X playlist format Xtream-style? | Yes, with endpoint paths isolated in configuration | `iptv/xtream/XtreamEndpointConfig.kt` |
| 2 | Backend base URL and hosting | Placeholder backend URL; mock default | `local.properties`, `AppContainer` |
| 3 | How many addons can a user configure? | Support multiple independent configurations | `domain/model/Preferences.kt`, `data/prefs/`, `provider/htmljson/` |
| 4 | Should addon configuration sync across devices? | No, device-local by default | Addon preferences and backend contract |
| 5 | Minimum Fire TV generation | `minSdk 24` | `app/build.gradle.kts` |
| 6 | Subtitle appearance controls | Language, size, background opacity | `presentation/settings/SubtitleSettings.kt` |
| 7 | Push notification transport | Local implementation only in v1 | `NotificationRepository` |
| 8 | Sync conflict policy | Last-write-wins on `updatedAt` | `data/backend/SyncPolicy.kt` |
| 9 | DRM support | No unsupported DRM; fail clearly | `playback/PlayerManager.kt` |
| 10 | Analytics/crash reporting | None in v1 | `core/log/` |

---

## 10. Toolchain note

On the documented Windows environment, Gradle requires `TEMP` and `TMP` to point to `C:\gradle-tmp` before it can establish the local socket used for daemon communication.

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
export TEMP='C:\gradle-tmp' TMP='C:\gradle-tmp'
./gradlew.bat :app:assembleDebug
```

Android Studio uses the normal Windows temporary directory, so the same environment-variable workaround may need to be applied system-wide.