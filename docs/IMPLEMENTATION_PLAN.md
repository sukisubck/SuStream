# SuStream — Fire TV / Android TV Implementation Plan

**Status:** living document · **Created:** 2026-09-01 · **Target:** Amazon Fire TV + Android TV / Google TV

Inputs analysed before writing this plan:

| Input | What it gave us |
| --- | --- |
| `~/Downloads/prototype.html` | Visual language, interaction model, screen inventory, mock data shapes. React/Tailwind prototype with three "modes" (Mobile app, Web companion, Admin portal). |
| `~/Downloads/Structure.xlsx` → sheet `Features` | 58 features across 14 modules. This is the feature baseline; see `TRACEABILITY.md`. |
| `~/Downloads/Structure.xlsx` → sheet `Summary` | Workstream/effort breakdown (UI/UX, Mobile App, TMDB, Backend/API, Source integration, Provider integration, Player, Admin, QA, DevOps, PM/BA). Effort column was blank in the supplied file. |
| Repository | An existing Android Studio **TV Compose** scaffold (`SuStream`), AGP 9.3.2 / Kotlin 2.2.10 / Gradle 9.5.0, `LEANBACK_LAUNCHER` already declared, one monolithic `MainActivity.kt` with a hard-coded sidebar + rails. |

---

## 1. Contradictions and risks found in the inputs

These were resolved before coding rather than discovered during it.

### 1.1 The prototype is a phone/web app; the brief is a TV app

`prototype.html` is built around a 390×844 phone frame with a **bottom tab bar**, tap targets, hover
states, horizontal scroll strips and a device-bezel mock. None of that survives contact with a
remote control. Decisions taken:

| Prototype pattern | TV replacement | Why |
| --- | --- | --- |
| Bottom tab bar (4 tabs) | Left **nav rail** that expands on focus | D-pad-reachable, matches the existing scaffold, standard on Android TV. |
| `hover:` styling | `focused` styling (scale + border + elevation) | There is no cursor on a TV. |
| `overflow-x: auto` strips | `LazyRow` with focus-driven scrolling | Scrollbars are unusable with a D-pad. |
| Modal dialogs with an "x" close button | Full-width TV dialogs, dismissed with **BACK** | Remote has no pointer to hit a small glyph. |
| 10–11 px type | 12 sp absolute floor, body ≥ 16 sp | 10-foot viewing distance. |
| `select-none`, custom scrollbars | Dropped | Web-only concerns. |
| Admin portal inside the client | **Removed from the APK** (see 1.4) | Privilege boundary. |

The **design language** is kept faithfully: the `#6C5CE7`/`#A29BFE` violet brand ramp, the
`#0B0E14`/`#161B24` near-black surfaces, amber (`#F59E0B`) as the IPTV accent, emerald for healthy
status, rose for LIVE, 2xl/3xl rounded corners, Inter/Manrope typography, poster cards at 2:3 with a
rating pill top-right, hero backdrop with a double gradient scrim, and the amber-accented IPTV hub
header. See `docs/DESIGN_SYSTEM.md`.

### 1.2 The prototype's core flow is a torrent/piracy workflow — the brief forbids it

This is the single biggest contradiction and it is **not** a style question.

The prototype's `startResolverFlow()` shows *"Scraping Sources… Checking trackers & cached debrid
hashes"*, then a source list carrying `seeders: 342`, `quality: '4K HDR10+ REMUX'`,
`provider: 'Real-Debrid (Cached)'`, resolved through `resolveViaDebrid()`. That is a torrent-index
scraper feeding a debrid unrestrictor: the standard architecture for streaming infringing copies.

The brief itself instructs (§1.3, §5 "Authorised provider integration"): *"Do not implement
piracy-oriented scraping, torrent indexing, copyright circumvention, or unlicensed content
aggregation… If the requested torrent scraper or provider workflow could facilitate copyright
infringement, stop and explain the limitation."*

**Resolution — what is built:**

- `AuthorisedSourceRepository` is a *contract only*. It describes how a lawful source provider is
  queried and how its results map onto a TMDB title. Two adapters ship:
  - `MockAuthorisedSourceRepository` — deterministic fixtures so every downstream screen, view
    model and test is exercisable offline. Clearly labelled as demo data in the UI.
  - `IptvBackedSourceRepository` — matches a TMDB title against channels/VOD entries in the user's
    **own** authorised IPTV playlists. This is a real, lawful source of playable links.
- `TorBoxRepository` is scoped to **the user's own cloud library**: account status, the items
  already in their account, and requesting a download link for a file *they* own. There is no
  search over torrent indexes, no magnet/hash lookup, no "cached hash" probing.
- Vocabulary is de-piracy-fied throughout: no `seeders`, no `trackers`, no `REMUX`, no
  "scraping". The UI says *"Checking your authorised sources"* and labels every result with the
  provider that authorised it.
- `PlayableSource.authorisation` is a required, non-nullable field. A source with no recorded
  authorisation basis cannot be constructed, so it can never reach the player.

**Not implemented, deliberately:** torrent/DDL index scraping, magnet resolution, hash-cache
probing, DRM circumvention, geo-unblocking, and the prototype's Real-Debrid / AllDebrid /
Premiumize "unrestrict any link" flow. See `docs/DEFERRED_AND_RESTRICTED.md` for the full list and
the reasoning per item.

A third adapter — a generic client for JSON-manifest addons the user configures themselves — is
described in §1.6. It is a *transport*, in the same sense that M3U support is a transport, and it is
constrained so that the excluded workflow above cannot travel over it.

### 1.3 Workbook feature "X Playlist Support" is underspecified

Sheet `Features` row 37 reads *"Support the client's specified X playlist format/provider"* with no
field names, base path or auth scheme. The brief calls it "XURL/XC-style". This is almost certainly
**Xtream Codes**, which the prototype confirms (`playlistType === 'xtream'`, fields
`xtreamServer` / `xtreamUser` / `xtreamPass`, `type: 'Xtream Codes'`, server
`http://iptv-pro.stream:8080`).

The brief says *"without assuming undocumented field names"*. So: the Xtream client is implemented
against the widely-documented `player_api.php` shape, but every path and parameter name lives in
**one** overridable config object (`XtreamEndpointConfig`), not scattered through the code. If the
client's provider differs, one file changes. The form is labelled with exactly the three things
every such provider needs (server URL, username, password) and nothing invented beyond that.

### 1.4 The workbook asks for an Admin Panel inside the client

Sheet `Features` rows 54–59 ask for user management, TMDB configuration, provider configuration,
IPTV management and system logs. The prototype puts these in the TV client as an "Admin Portal"
tab, including a field that displays a TMDB API key (`adminApiKey`) in the UI.

Shipping that in a public APK would mean the privileged surface, and the credentials it edits, are
on every user's device — anyone can decompile the APK or hook the process. **The admin panel is
therefore not in the APK.** Instead:

- `backend-contract/openapi.yaml` defines the admin surface as authenticated server endpoints with
  role checks and audit logging.
- `docs/ADMIN_BOUNDARY.md` specifies the separate admin web app, its authorisation model, and what
  the client is allowed to see (its *own* diagnostics, never global state).
- The client ships a read-only **Diagnostics** screen in Settings: integration health for *this
  device's* configured integrations, with all secrets redacted.

### 1.5 Smaller inconsistencies

| # | Issue | Resolution |
| --- | --- | --- |
| 1 | Prototype shows "Recently Added"; TMDB has no per-instance "recently added" concept. | Home rail uses TMDB `now_playing` (film) / `on_the_air` (TV) and is labelled *"New releases"*. Workbook row 5's "recently added" is satisfied for IPTV by playlist ingest date. |
| 2 | Prototype hero shows `duration: '2h 18m'` for a TV show context and rating as a string. | Domain model stores `runtimeMinutes: Int?` and `voteAverage: Double?`; formatting is a presentation concern. |
| 3 | Prototype `MOCK_HERO.rating = '8.9'` vs TMDB 0–10 float with vote counts. | Ratings shown to 1 dp with vote count; titles below a vote-count floor show no rating rather than a misleading one. |
| 4 | Workbook rows 6 and 34 both say "Continue Watching". | One implementation (`HistoryRepository.continueWatching()`), surfaced on Home and in Library. Traceability maps both rows to it. |
| 5 | Prototype `notifications` are fabricated provider adverts ("Real-Debrid Sync Active (284 days remaining)"). | Notifications become a real abstraction over two sources: integration-health alerts and watchlist-title availability. No invented account telemetry. |
| 6 | Prototype "Web Companion" mode. | Out of scope for an APK. Noted in `DEFERRED_AND_RESTRICTED.md`; the backend contract is transport-agnostic so a web client can reuse it. |
| 7 | Guest mode (row 3) vs watchlist/history sync (rows 31–34). | Local-first: guests get full local watchlist/history in Room. Signing in merges local state up. Documented in §5.4. |
| 8 | Prototype admin claims `scrapeSuccessRate`, `debridUptime`, `tmdbCacheHits`. | Only metrics we can actually measure are exposed; no fabricated SLAs. |

---

### 1.6 User-configured JSON manifest addons

A generic client for addons that expose a JSON manifest over HTTP
(`StremioAddonSourceRepository`, `provider/stremio/`). It is a **capability, not an integration**:
the app ships no addon, bundles no addon directory, performs no addon discovery, and recommends
none. The only addon it will ever talk to is one whose URL the user has typed in and explicitly
authorised.

**Why this is a different thing from §1.2.** §1.2 excludes a *workflow* — querying indexes for a
title and turning the results into streams via a debrid unrestrictor. It does not exclude a
*transport*. The app already consumes two user-supplied transports (M3U/M3U8 and Xtream), and this
is a third. The mechanism is neutral in the same way HTTP is neutral; what makes a source lawful is
the user's relationship with the service behind it, which is why every source carries an
[`Authorisation`].

**The constraints that keep the excluded workflow out.** These are load-bearing, not decoration.
Removing any of them changes what this adapter is, and should be treated as a product decision
rather than a refactor:

| Constraint | Effect |
| --- | --- |
| Only stream objects carrying a direct `url` are modelled | Torrent/P2P descriptors (`infoHash`, `fileIdx`) are not deserialised at all, so a P2P stream cannot be represented, let alone played |
| `behaviorHints.notWebReady` responses are dropped | That flag is precisely how an addon marks a stream needing a torrent or local-transport client |
| `https://` only, in every build type | Checked twice — before listing, and again before resolving |
| Every URL passes `UrlValidator` with `Usage.APP_SERVICE` | Scheme allowlist, SSRF host rules, no HTTPS→HTTP downgrade |
| Reachability probed before a `ResolvedStream` is produced | Nothing reaches ExoPlayer that has not answered a ranged GET |
| `Authorisation.UserAddon(addonId, addonName)` is mandatory | The sources sheet names the addon a stream came from; provenance is shown, never implied |
| `StremioAddonPreferences.authorisedByUser` must be true | A URL alone is not enough; the user has to confirm they are entitled to use that service |

**What the app does not, and cannot, do here.** It does not verify that the content an addon serves
is licensed — no client can. That is the same position as M3U: SuStream ships no playlists and
cannot audit what a provider carries. What the app *can* do, and does, is refuse the transports
associated with infringement, require an explicit authorisation acknowledgement, record the basis on
which every source is playable, and show the user which addon each stream came from. Responsibility
for configuring only services the user is entitled to use rests with the user, and the settings copy
says so.

**Not to be added on top of this.** An addon directory or catalogue browser; a "popular addons"
list; support for `infoHash`, `fileIdx`, magnet or `externalUrl` stream descriptors; a debrid
unrestrict step applied to addon results; or any default addon URL shipped in the build. Each of
those converts a neutral transport into the §1.2 workflow.

**Setup is gated twice.** An addon cannot be stored until both of these hold, and editing the URL
clears both — so a verified address cannot be swapped for an unverified one between testing and
saving:

1. **It has been probed.** `AddonManifestProbe` fetches `manifest.json`, confirms the addon
   advertises the `stream` resource, and shows its own name back to the user. An addon that supplies
   only catalogues or metadata is valid and useless here, and is rejected at setup rather than
   presenting as a permanently empty availability panel. The probe runs on the *same* HTTP client the
   adapter uses, so anything that verifies is reachable under the timeouts and TLS policy playback
   will later apply.
2. **The user has confirmed entitlement.** This is what `authorisedByUser` records, and the adapter
   returns nothing without it. It is a precondition of the save action rather than a pre-ticked box —
   the difference between a consent record and a dark pattern — and it must never default to true.

`normaliseAddonBaseUrl()` is shared between the probe and the adapter deliberately. Two normalisers
would drift, and the failure mode is nasty: an addon that tests clean at setup and silently returns
nothing at playback.

**Where the code is:** `provider/stremio/StremioAddonApi.kt` (wire types),
`StremioAddonSourceRepository.kt` (adapter), `AddonManifestProbe.kt` (setup verification and shared
URL normalisation), `domain/model/Playback.kt` (`Authorisation.UserAddon`),
`domain/model/Preferences.kt` (`StremioAddonPreferences`), `presentation/settings/`
(`AddonSettingsViewModel`; the screen itself is outstanding), and one entry in the composite in
`core/di/AppContainer.kt`. It sits **after** the IPTV adapter and before the demo adapter, so a
user's own playlists are always preferred.

**One addon, not many.** `StremioAddonPreferences` currently models a single addon — one URL, one
display name, one consent flag. Supporting several means changing the preferences model, the
DataStore keys and the adapter (most likely one adapter instance per configured addon, which the
composite already accommodates). Open decision; cheaper to settle before the settings screen exists.

[`Authorisation`]: ../app/src/main/java/com/sustream/tv/domain/model/Playback.kt


## 2. Architecture

### 2.1 Choice

**Single Gradle module (`:app`), strict package boundaries, MVVM with unidirectional data flow,
manual dependency injection.**

```
presentation  →  domain (use cases + repository interfaces)  ←  data / iptv / provider / playback
      ↑                                                                    │
      └──────────────── immutable UiState, one-shot UiEvent ───────────────┘
```

- **View models** expose a single `StateFlow<XUiState>` and accept intents as method calls. No
  Compose type ever crosses into `domain`; no `data` type ever reaches `presentation`.
- **Domain** holds pure Kotlin: models, repository *interfaces*, use cases. No Android imports, so
  it is unit-testable on the JVM without Robolectric.
- **Data/iptv/provider** implement the domain interfaces. Every external integration sits behind
  one, exactly as the brief requires:
  `TmdbRepository`, `IptvPlaylistRepository`, `AuthorisedSourceRepository`, `TorBoxRepository`,
  `PlaybackRepository`, plus `AuthRepository`, `WatchlistRepository`, `HistoryRepository`,
  `SettingsRepository`, `NotificationRepository`.

### 2.2 Rationale, and the alternatives rejected

| Decision | Rationale | Rejected alternative |
| --- | --- | --- |
| **Compose for TV** (`androidx.tv:tv-material` 1.1.0) | Stable since 1.0.0. The existing scaffold already uses it, `tv-material` gives correct TV focus/colour defaults, and Compose's focus system is far easier to reason about than Leanback's fragment/presenter chain. | **Leanback** — materially better device compatibility only below API 21, which we do not target. Its `BrowseSupportFragment` also fights the prototype's layout. |
| **Single module** | Build reliability on an AGP 9 / Kotlin 2.2 / JDK 25 toolchain that is very new; a `:core`/`:data`/`:feature-*` split multiplies configuration risk for a codebase this size without buying much. Package boundaries are enforced by review and by keeping `domain` Android-free. | Multi-module — revisit when a second client (phone/web) shares the domain. Migration is mechanical because the boundaries already exist. |
| **Manual DI** (`AppContainer` + `ViewModelFactory`) | Zero annotation processing for DI, so no Hilt/AGP-9/JDK-25 bytecode-transform risk. Constructor injection everywhere means tests build objects directly with no framework. The graph is ~40 objects — small enough to read in one file. | **Hilt** — adds a Gradle plugin doing bytecode transformation plus KSP on a toolchain this new. The cost/benefit does not hold at this size. |
| **Room + KSP** for persistence | The brief specifies Room; it gives typed queries, migrations and `Flow` observation. Exactly one annotation processor in the build. | Hand-rolled SQLite (loses migrations/type safety); SQLDelight (another new toolchain surface). |
| **Retrofit 3 + OkHttp 5 + kotlinx.serialization** | `retrofit2:converter-kotlinx-serialization` is now first-party, so no third-party converter. `kotlinx.serialization` is compiler-plugin based, so no reflection and no extra processor. OkHttp gives us one place for timeouts, redaction, cache and TLS policy. | Moshi (needs KSP/reflection); Ktor client (fine, but Retrofit's interface-per-API maps cleanly onto our repository seams). |
| **Media3 1.11.0** | HLS + progressive + DASH, subtitle and audio track selection, `PlayerSurface` for Compose. | Native `MediaPlayer` — no HLS track selection worth using. |
| **Navigation Compose 2.10.0** | Type-safe routes, and a single place to define TV back behaviour. | Custom state machine — reinvents back-stack handling we need to get right. |
| **No WorkManager in v1** | The only candidate job is playlist/EPG refresh. Fire TV aggressively restricts background work and devices are usually mains-powered and always-on; refresh-on-open plus an explicit *Refresh* action is more predictable and testable. | WorkManager — planned for Phase 2 if EPG windows prove too stale in practice. |

### 2.3 Package layout

```
app/src/main/java/com/sustream/tv/
├── SuStreamApplication.kt          Application; owns AppContainer
├── MainActivity.kt                 Single activity; hosts the nav graph
├── core/
│   ├── config/                     AppConfig, RuntimeFlags, secret plumbing
│   ├── di/                         AppContainer, ViewModelFactory
│   ├── net/                        OkHttp factory, UrlValidator, interceptors, size/redirect limits
│   ├── result/                     AppResult<T>, AppError taxonomy
│   ├── log/                        AppLog + secret redaction
│   └── util/                       Clock, formatters, Dispatchers provider
├── designsystem/
│   ├── theme/                      Colour, Type, Dimens, Shape, SuStreamTheme
│   ├── component/                  Cards, rails, buttons, chips, state views, dialogs
│   └── focus/                      Focus restoration + D-pad helpers
├── domain/
│   ├── model/                      MediaItem, MediaDetails, Season, Episode, PlayableSource, …
│   ├── repository/                 The 10 repository interfaces
│   └── usecase/                    One class per user-visible operation
├── data/
│   ├── tmdb/                       API, DTOs, mappers, cache, image URL builder
│   ├── local/                      Room database, entities, DAOs
│   ├── prefs/                      DataStore settings, encrypted credential store
│   ├── backend/                    Backend API + mock implementation
│   └── mock/                       Offline catalogue fixtures
├── iptv/
│   ├── m3u/                        M3U/M3U8 parser
│   ├── xtream/                     Xtream Codes client (configurable endpoints)
│   ├── epg/                        XMLTV parser
│   └── IptvPlaylistRepositoryImpl.kt
├── provider/
│   ├── torbox/                     TorBox API + repository + mock
│   └── source/                     AuthorisedSourceRepository implementations
├── playback/                       PlayerManager, track state, progress reporting
└── presentation/
    ├── navigation/                 Routes + nav graph + back policy
    ├── home/ movies/ tvshows/ details/ search/ library/ iptv/ epg/
    ├── settings/ diagnostics/ auth/ player/
    └── common/                     UiState contracts, shared view-model plumbing
```

---

## 3. Screen map and navigation graph

```
                    ┌──────────────────── MainActivity (single) ────────────────────┐
                    │  NavRail: Home · Films · TV · Live TV · Search · Library · ⚙  │
                    └───────────────────────────────────────────────────────────────┘

  Splash ─┬─▶ (first run) Onboarding ─▶ Auth {SignIn | SignUp | Continue as guest} ─┐
          └─▶ (returning) ────────────────────────────────────────────────────────▶ Home

  Home ──┬─▶ Details(movie|tv)  ──┬─▶ Sources sheet ─▶ Player
         │                        ├─▶ Seasons ─▶ Episodes ─▶ Sources sheet ─▶ Player
         │                        └─▶ Watchlist / Watched toggles
         ├─▶ Films (grid + genre/year filters, paged)
         ├─▶ TV shows (grid + filters, paged)
         ├─▶ Search (query → films | shows | channels)
         ├─▶ Live TV ─┬─▶ Channels ─▶ Player(live)
         │            ├─▶ TV guide (EPG grid) ─▶ Player(live)
         │            ├─▶ Favourites
         │            └─▶ Playlists ─┬─▶ Add playlist {M3U URL | M3U file | Xtream}
         │                           └─▶ Playlist detail (edit / refresh / delete)
         ├─▶ Library ─┬─▶ Watchlist
         │            ├─▶ Continue watching
         │            └─▶ History
         └─▶ Settings ─┬─▶ Account        ├─▶ Playback     ├─▶ Subtitles
                       ├─▶ Providers      ├─▶ IPTV         ├─▶ Diagnostics
                       ├─▶ Addon (§1.6) ─▶ URL · test · confirm entitlement · remove
                       └─▶ About / attribution / legal / clear data
```

**TV back-button policy** (one place: `presentation/navigation/BackPolicy.kt`)

| Context | BACK does |
| --- | --- |
| Player, controls visible | Hide controls |
| Player, controls hidden | Confirm-free exit to the previous screen, progress saved first |
| Player, a track/settings sheet open | Close the sheet only |
| Any dialog / sources sheet | Close it, restore focus to the invoking control |
| A section root (Home/Films/TV/Live/Search/Library/Settings) | Move focus to the nav rail |
| Nav rail focused, not on Home | Go to Home |
| Nav rail focused, on Home | Double-press-to-exit with a snackbar hint |

The rule the brief calls out — *"does not unexpectedly exit playback"* — is enforced by making the
player's BACK handling a state machine, and by saving progress **before** any navigation.

**Focus management:** every rail and grid remembers its last-focused index
(`rememberFocusRestorer`-backed helpers in `designsystem/focus/`), so returning from Details lands
on the card you came from, not the first item.

---

## 4. Data models (domain)

Trimmed to the essentials; full definitions live in `domain/model/`.

```kotlin
enum class MediaType { MOVIE, TV }

data class MediaItem(                 // rail/grid card
    val id: MediaId,                  // value class over "movie:603" / "tv:1396"
    val type: MediaType,
    val title: String,
    val posterPath: String?,          // TMDB path, not a URL — URL built at render time
    val backdropPath: String?,
    val releaseYear: Int?,
    val voteAverage: Double?,         // null when vote count is below the display floor
    val voteCount: Int,
    val primaryGenre: String?,
)

data class MediaDetails(
    val item: MediaItem,
    val overview: String,
    val genres: List<Genre>,
    val runtimeMinutes: Int?,         // films
    val releaseDate: LocalDate?,
    val cast: List<CastMember>,
    val seasons: List<Season>,        // empty for films
    val certification: String?,
)

data class Season(val seasonNumber: Int, val name: String, val episodeCount: Int, val posterPath: String?)
data class Episode(
    val seasonNumber: Int, val episodeNumber: Int, val name: String,
    val overview: String, val runtimeMinutes: Int?, val stillPath: String?, val airDate: LocalDate?,
)

// ---- Playback availability -------------------------------------------------
/** Basis on which a source is allowed to play. Non-nullable by design. */
sealed interface Authorisation {
    /** A channel/VOD entry from a playlist the user added themselves. */
    data class UserPlaylist(val playlistId: String, val playlistName: String) : Authorisation
    /** A file already present in the user's own provider cloud account. */
    data class UserProviderLibrary(val provider: String, val accountRef: String) : Authorisation
    /** Fixture data. Never resolvable to a real network stream. */
    data object Demo : Authorisation
}

data class PlayableSource(
    val id: String,
    val label: String,               // "1080p · English · Sky Sports Main Event"
    val container: StreamContainer,  // HLS | DASH | PROGRESSIVE
    val authorisation: Authorisation,
    val providerName: String,
    val isLive: Boolean,
    val qualityLabel: String?,       // from provider metadata only; never inferred
)

/** A source that has been validated and is ready for the player. */
data class ResolvedStream(
    val source: PlayableSource,
    val uri: String,                 // https only in release builds
    val headers: Map<String, String>,// redacted in all logs
    val expiresAt: Instant?,
    val subtitleTracks: List<SubtitleTrack>,
)

enum class SourceAvailability { AVAILABLE, NONE_CONFIGURED, NO_SOURCE_FOUND, CHECKING, ERROR }

// ---- IPTV ------------------------------------------------------------------
sealed interface PlaylistOrigin {
    data class M3uUrl(val url: String) : PlaylistOrigin
    data class M3uFile(val documentUri: String, val displayName: String) : PlaylistOrigin
    data class Xtream(val serverUrl: String, val username: String) : PlaylistOrigin  // password in secure store
}
data class Playlist(
    val id: String, val name: String, val origin: PlaylistOrigin,
    val channelCount: Int, val lastSyncedAt: Instant?, val status: PlaylistStatus,
    val epgUrl: String?,
)
enum class PlaylistStatus { OK, NEVER_SYNCED, PARSE_FAILED, UNREACHABLE, AUTH_FAILED }

data class Channel(
    val id: String, val playlistId: String, val number: String?, val name: String,
    val logoUrl: String?, val group: String?, val tvgId: String?, val streamUrl: String,
    val isFavourite: Boolean,
)
data class EpgProgramme(
    val channelTvgId: String, val title: String, val description: String?,
    val start: Instant, val end: Instant,
)

// ---- Progress --------------------------------------------------------------
data class PlaybackProgress(
    val mediaId: MediaId, val seasonNumber: Int?, val episodeNumber: Int?,
    val positionMs: Long, val durationMs: Long, val updatedAt: Instant,
) { val fraction get() = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f }
```

`AppResult<T>` is a sealed `Success | Failure(AppError)`; `AppError` is a closed taxonomy
(`Network`, `Timeout`, `Unauthorised`, `RateLimited(retryAfter)`, `NotFound`, `ParseFailed`,
`SchemeRejected`, `TooLarge`, `Expired`, `QuotaExceeded`, `UnsupportedFormat`, `Unknown`) so every
UI error state is exhaustive at compile time.

---

## 5. API and authentication assumptions

### 5.1 TMDB

- **Base:** `https://api.themoviedb.org/3`. Auth: `Authorization: Bearer <v4 read token>`.
- **Images:** never hard-coded. `GET /configuration` supplies `images.secure_base_url` and the
  valid size lists; `TmdbImageUrlBuilder` picks the smallest size ≥ the requested display width and
  caches the configuration for 24 h. Falls back to `https://image.tmdb.org/t/p/` with documented
  sizes if configuration is unavailable.
- **Endpoints used:** `trending/{movie,tv}/week`, `movie/popular`, `tv/popular`,
  `movie/now_playing`, `tv/on_the_air`, `movie/{id}` + `append_to_response=credits`,
  `tv/{id}` + `credits`, `tv/{id}/season/{n}`, `search/multi`, `discover/{movie,tv}`
  (genre + year filters), `genre/{movie,tv}/list`, `configuration`.
- **Pagination:** TMDB caps at page 500; `PagingSource` clamps to that and stops cleanly.
- **Rate limits:** TMDB no longer publishes a fixed number, so the client is defensive rather than
  tuned to a guess — max 4 concurrent TMDB calls, exponential backoff with jitter on 429 honouring
  `Retry-After`, and an OkHttp disk cache (50 MB) with a 4-hour `stale-while-revalidate`.
- **Attribution:** required TMDB wording and logo on Settings → About, and a per-screen
  "Metadata by TMDB" credit on Details. TMDB supplies **metadata only** — the UI never implies it
  provides video. `SourceAvailability` is computed independently of metadata availability.
- **Assumption flagged:** TMDB terms prohibit certain uses. Before release, confirm the current
  attribution wording and that the deployment is registered. `TODO(tmdb-terms)` in
  `TmdbAttribution.kt`.

### 5.2 Backend (does not exist yet)

Defined in `backend-contract/openapi.yaml`, mocked in-app by `MockBackendService`. The client talks
to `BackendApi` only; swapping mock → real is one line in `AppContainer`.

`POST /auth/signup`, `POST /auth/login`, `POST /auth/refresh`, `POST /auth/logout`,
`GET|PATCH /profile`, `GET|PUT|DELETE /watchlist`, `GET|PUT /history`, `PUT /progress`,
`GET /providers/status`, `POST /providers/{id}/connect`, `DELETE /providers/{id}`,
`POST /sources/lookup`, `POST /sources/resolve`, `GET /health`.

**Auth model:** short-lived access JWT (15 min) + rotating refresh token. Access token in memory
only; refresh token in `EncryptedSharedPreferences`. One `Authenticator` handles refresh with
single-flight de-duplication so a burst of 401s triggers one refresh, and logs out on refresh
failure. Guest mode issues no tokens at all and never calls authenticated endpoints.

### 5.3 TorBox

- **Base:** `https://api.torbox.app/v1/api`. Auth: `Authorization: Bearer <api key>`.
- **Scope (deliberately narrow):** account status, the user's existing cloud items, and requesting
  a download link for a file the user already owns.
- **Production path:** the API key never leaves the backend. The client calls
  `POST /providers/torbox/connect` and `POST /sources/resolve`; the backend holds the key. A
  direct-from-device mode exists for local development only, gated on `BuildConfig.DEBUG` **and** an
  explicit developer toggle.
- **Assumption flagged:** exact response field names are pinned in `TorBoxDto.kt` with
  `TODO(torbox-contract)` markers, tolerant deserialisation (unknown keys ignored, every optional
  field nullable) and an error mapper that degrades to `AppError.Unknown` rather than crashing. Any
  field not confirmed against TorBox's official documentation is marked as such.
- A `MockTorBoxRepository` backs all development and tests, so no test run touches the live API.

### 5.4 Local vs synchronised state

| Data | Guest | Signed in |
| --- | --- | --- |
| Watchlist, history, progress, favourites | Room, device-only | Room is the source of truth; a sync worker pushes deltas and pulls remote changes, last-write-wins on `updatedAt` |
| IPTV playlists + credentials | Room + encrypted store, **never synced by default** | Same. Sync is opt-in per playlist, and credentials are never uploaded |
| Provider tokens | Encrypted store (dev mode only) | Backend-held |
| Settings | DataStore, device-only | Device-only |

Signing in for the first time **merges** local state upward rather than discarding it. Signing out
clears tokens and offers, but does not force, clearing local data.

---

## 6. Security and privacy risks

Full treatment in `docs/SECURITY.md`. The risks that shaped the architecture:

| Risk | Mitigation |
| --- | --- |
| Secrets in the APK | No secret in source control. `local.properties` (gitignored) for dev only; production secrets live behind the backend. `.env.example` carries placeholders only. |
| IPTV credentials | `EncryptedSharedPreferences` (AES-256-GCM, keystore-backed master key). Never logged, never in crash reports, never in `toString()` — password fields are wrapped in a `Secret` type whose `toString()` returns `"***"`. |
| Malicious playlist / EPG input | Treated as hostile: HTTPS-preferred scheme allowlist (`http` permitted only for IPTV, where providers still require it, and only with an explicit user acknowledgement), rejection of `file`/`content`/`javascript`/`data`, 10 MB playlist and 25 MB EPG size caps, 30 s timeouts, max 3 redirects with no cross-scheme downgrade, line-count and line-length caps, XMLTV parsed with DTD/external entities **disabled** (XXE), and a hard cap on parsed entities. |
| SSRF via user-supplied URLs | Client-side: reject non-public hosts (loopback, link-local, RFC1918, `.local`, IPv6 ULA) unless the user has explicitly enabled LAN playlists for a home server. Server-side rules specified in `SECURITY.md` §4. |
| Untrusted remote JSON | Strict `kotlinx.serialization` with explicit nullability; no `Any`/dynamic types; response size caps; content-type checks; a resolved stream URI must be `https` in release builds and must parse to an allowed scheme before it reaches ExoPlayer. |
| Token leakage in logs | A single `AppLog` facade with an interceptor that redacts `Authorization`, `api_key`, `token`, `password`, and Xtream query parameters. `HttpLoggingInterceptor` is debug-only and header-redacting. |
| Privileged admin surface in the client | Removed from the APK (§1.4). |
| Data minimisation | No analytics SDK in v1. Diagnostics are local and shown to the user before any upload, which is opt-in and off by default. Account deletion endpoint specified in the contract. |
| Content controls | TMDB certifications surfaced on Details; `include_adult=false` on every TMDB call; a PIN-gated maturity setting is specified but deferred (see `DEFERRED_AND_RESTRICTED.md`). |

---

## 7. Legal and compliance boundaries

1. **No unauthorised content discovery.** No torrent/DDL index scraping, no magnet or hash
   resolution, no cached-hash probing, no bundled playlists. `AuthorisedSourceRepository` is a
   contract with a mock adapter, a user-playlist adapter and a user-configured addon adapter.
2. **No circumvention.** No DRM bypass, no geo-restriction evasion, no defeating provider access
   controls. If a stream is DRM-protected and we hold no licence, playback fails with a clear
   message.
3. **User-supplied IPTV only.** The user enters their own service details. The app ships zero
   channel lists and does not discover providers.
3a. **User-configured addons only, and direct HTTPS only.** The same rule as (3), applied to the
   JSON-manifest transport in §1.6: no bundled addon, no addon directory, no discovery, no default
   URL. Peer-to-peer stream descriptors are not modelled, so they cannot be played. The app cannot
   verify what an addon serves — no client can — so the user confirms their entitlement before an
   addon is enabled, and the sources sheet names the addon behind every stream.
4. **Provider integration stays inside the user's own account.** TorBox access is limited to the
   user's own library.
5. **TMDB attribution and terms** honoured; metadata availability is never presented as playback
   availability.
6. **Reporting and takedown** placeholders in `SECURITY.md` §11, to be completed with real contact
   details before release.
7. **UK/EU data protection:** data minimisation, purpose limitation, an account deletion path, and
   no third-party trackers in v1. A DPIA is listed as a pre-release task, not a code task.

---

## 8. Phased delivery plan

| Phase | Content | Exit criteria |
| --- | --- | --- |
| 0 | Inspect prototype, workbook, repository. Diagnose toolchain. | This document. Baseline `assembleDebug` green. |
| 1 | Docs: plan, API contract, security, test plan, traceability, design system, admin boundary, deferred list. | Reviewable without reading code. |
| 2 | Build config: version catalog, namespace, TV manifest, secret plumbing, `.env.example`, `AppContainer` skeleton. | `assembleDebug` green with the new dependency set. |
| 3 | Design system from the prototype: colour, type, dimens, focus, cards, rails, buttons, chips, state views, dialogs. | Component previews render at 1920×1080. |
| 4 | Navigation, nav rail, static screens with fixture data, back policy. | Every screen reachable by D-pad; no dead ends. |
| 5 | TMDB: API, DTOs, mappers, cache, paging, image URLs, mock fallback. | Home/Films/TV/Search/Details on live TMDB, and fully offline on mocks. |
| 6 | Room, DataStore, secure store; watchlist, history, resume, favourites. | Survives process death; guest and signed-in paths both work. |
| 7 | IPTV: M3U/M3U8 parser, Xtream client, XMLTV EPG, channels/categories/favourites/guide, playlist CRUD, document picker. | Adding a lawful playlist yields a browsable, playable channel list; malformed input surfaces a clear error. |
| 8 | `AuthorisedSourceRepository` + mock, `TorBoxRepository` + mock, resolution and validation, source selection UI. | Sources listed, selected and validated; every failure mode has a UI state. |
| 9 | Media3 playback: controls, seek/FF/RW, resume, subtitles, audio tracks, buffering/error/retry, lifecycle. | The six journeys in `TEST_PLAN.md` pass on a Fire TV device or emulator. |
| 10 | Settings, diagnostics, attribution, clear-data, admin boundary docs. | All workbook Settings rows satisfied. |
| 11 | Unit + UI tests, lint, CI workflow. | `test`, `lint`, `assembleDebug` green. |
| 12 | Final build; report commands, output, remaining issues. | Honest status report. |

### 8.1 Where delivery has actually reached

Phases are not being completed strictly in order — the entry point was brought forward so there was
something installable to click through. Current state, which `docs/HANDOVER.md` tracks in detail:

| Phase | State |
| --- | --- |
| 0–3 | Done. Toolchain diagnosed (see §10), build config, design system. |
| 4 | Done. Nav rail, nav graph, entry point, TV back policy. Five sections still render placeholders. |
| 5 | Done. Live TMDB with an offline mock fallback. |
| 6 | Done. Room, DataStore, encrypted store, watchlist/history/resume/favourites. |
| 7 | Parsers, Xtream client and repositories done; **Live TV screens done**, not yet exercised on a device. |
| 8 | Contract, mock, IPTV-backed and addon adapters, TorBox + mock, composite, sources sheet — all done. |
| 9 | Media3 engine and player screen done; unverified on hardware. |
| 10 | **In progress.** Addon settings logic done (§1.6); the settings screens themselves are outstanding. |
| 11 | Not started. No tests exist yet. |
| 12 | Not started. |

**Nothing has been run on a device.** Every "done" above means compiles, packages and is wired —
not that its runtime behaviour has been observed. First device run is the outstanding QA gate for
everything from phase 4 onward.

---

## 9. Unresolved questions, and the default taken

Each of these is a *config or interface* decision, not a blocker. The default is live; changing it
is a one-file edit.

| # | Question | Default taken | Where to change |
| --- | --- | --- | --- |
| 1 | Is "X playlist" Xtream Codes? | Yes — evidenced by the prototype. Paths/params isolated in one config object. | `iptv/xtream/XtreamEndpointConfig.kt` |
| 2 | Backend base URL and hosting? | `https://api.sustream.example` placeholder; mock backend is the default implementation. | `local.properties` → `BACKEND_BASE_URL`; `AppContainer` |
| 3 | Which authorised source provider will the client actually use? | None wired. Contract + mock only. | `provider/source/` |
| 4 | Minimum Fire TV generation? | `minSdk 24` — the floor imposed by androidx.navigation 2.10, and free in practice: Fire OS 6 is API 25, so every Fire TV Stick from the 2nd generation onward is covered and only end-of-life Fire OS 5 (API 22) is excluded. | `app/build.gradle.kts` |
| 5 | TorBox response field names beyond the documented core? | Tolerant parsing, every optional field nullable, `TODO(torbox-contract)` markers. | `provider/torbox/TorBoxDto.kt` |
| 6 | Subtitle appearance controls — how far? | Language + size + background opacity. Full CEA-708 styling deferred. | `presentation/settings/SubtitleSettings.kt` |
| 7 | Push notifications transport? | `NotificationRepository` abstraction with a local implementation. No FCM (absent on Fire TV). | `domain/repository/NotificationRepository.kt` |
| 8 | Sync conflict policy? | Last-write-wins on `updatedAt`. | `data/backend/SyncPolicy.kt` |
| 9 | Does the client need DRM? | No. If a source reports DRM we cannot licence, playback fails cleanly. | `playback/PlayerManager.kt` |
| 10 | Analytics/crash reporting vendor? | None in v1 (data minimisation). Diagnostics local and opt-in. | `core/log/` |

---

## 10. Toolchain note (environment, not code)

Gradle could not start on this machine before any code was written: every build failed with
`java.io.IOException: Unable to establish loopback connection`.

**Cause:** Windows AF_UNIX `connect` fails for socket files created anywhere under
`C:\Users\<user>\AppData\Local\Temp` (bind succeeds, connect returns `WSAEINVAL`). Since JDK 21,
`Selector.open()` builds its wakeup socketpair as an AF_UNIX socket in the *native* temp directory,
so `Selector.open()` fails 100% of the time and the Gradle client can never reach its daemon. Plain
TCP loopback works, `Pipe.open()` works, and the Winsock LSP catalog is clean — so
`netsh winsock reset` is not the fix.

**Workaround used for every build in this project** (note: `-Djava.io.tmpdir` alone does *not*
work, because the failing code reads the Win32 `GetTempPath()`):

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
export TEMP='C:\gradle-tmp' TMP='C:\gradle-tmp'
./gradlew.bat :app:assembleDebug
```

Android Studio uses the normal `%TEMP%`, so **IDE builds are expected to hit the same failure**. A
durable fix is either setting the user's `TEMP`/`TMP` environment variables to a clean path such as
`C:\gradle-tmp`, or identifying what breaks AF_UNIX under that folder — the likely candidates on
this machine are the Radmin VPN WFP filter drivers, the NordVPN TAP / OpenVPN DCO adapters, or
Docker Desktop's Hyper-V networking. See `README.md` § Troubleshooting.
