# Changelog

All notable changes to Phone TV. Versions follow `MAJOR.MINOR.PATCH`; the
version is shown in the app's footer and at the top of Settings.

## 0.9.1 — 2026-11-09

### Fixed
- **Up never reached the top menu on TVs.** ScrollView's built-in arrow
  handling only looks half a screen ahead; past that it took focus itself and
  the next press restarted from the bottom of the page. All D-pad movement is
  now decided by the app (`HomeActivity.moveFocus`), with Left/Right kept
  inside the current shelf.
- **Banner artwork was cropped at the top** in the full-width layout. In
  landscape the artwork now always shows at its natural 16:9.
- **Plex items didn't start playing.** Uses Plex's current deep-link format
  (`?viewOffset=…&autoPlay=1`). Tapping a Plex card now plays/resumes directly;
  long-press shows details.

### Changed
- Focused items show a clean outline only; the zoom is gone.
- Banner default is details left, artwork right. *Settings › Banner layout*
  flips it.
- Settings stay open while you make changes; **Done** or Back closes them.

### Added
- **Plex profiles.** *Settings › Plex profile* lists the profiles in your
  Plex Home and switches between them (with PIN where required), so Continue
  watching matches who's on the couch.

## 0.9.0 — 2026-11-09

TV release: NVIDIA Shield, Google TV / Android TV and Fire TV.

### Added
- **Runs on TV boxes.** Leanback launcher entry and banner, no touchscreen
  required, TV editions of apps preferred on TVs, minimum Android lowered to
  7.1 (Fire OS 6).
- **Remote navigation.** White focus ring and gentle zoom on every selectable
  item; smooth, centred scrolling instead of jumps; focus is restored after
  the screen refreshes; explicit Up/Down path between the banner and the top
  menu; first D-pad press always lands somewhere sensible; MENU opens app
  options or Settings; Back leaves the app when it isn't the Home screen.
- **On-screen keyboard** opens when a text box is selected with a remote.
- **Plex linking:** large code, *Copy code*, and Back keeps the same code
  alive (reopen Connect Plex to see it again).
- **De-bloat tools:** hide apps from the Apps tab, uninstall from an app's menu.
- **Replace the TV home screen (experimental, opt-in):** an accessibility
  service that brings Phone TV forward when the stock launcher appears, with
  a pause switch and crash back-off.
- Banner layout setting; TVs default to the Large display size.

### Changed
- Banner is shorter. In landscape the artwork sits on the left at full 16:9
  with details on the right; description text is larger.

### Not yet verified
- Built and lint-clean, but not run on Shield or Fire TV hardware by the
  maintainers at release time. Reports welcome.

## 0.8.0 — 2026-11-09

### Added
- **Built-in updates.** Phone TV checks `version.json` on launch and every
  12 hours, downloads a newer build in the background, verifies its SHA-256
  and shows an *Update ready* card; one tap hands it to Android's installer.
  Settings › Check for updates does the same on demand. The first time,
  Android asks to allow Phone TV to install updates.

### Fixed
- CI build: dropped the Android SDK setup action (broken on current runners;
  the SDK is preinstalled), moved to checkout/setup-java v5, and added the
  Gradle wrapper.

## 0.7.0 — 2026-11-09

### Added
- **Tune the hero.** A *Tune* button on the banner takes two or three words
  ("asian romance", "latest spanish", "nigerian comedy", "90s action",
  "black films") and fills the hero with matching titles, with a "More…" row
  for the rest. Languages, countries, genres, years and "latest" are
  understood; no account or API key. Backed by Wikidata + Cinemeta, cached
  for 12 hours, and built behind a `HeroMood.Source` interface so a hosted
  service can replace it later.

### Changed
- License is now **GPL-3.0** (0.6.0 and earlier remain MIT).
- Plex rows and the hero only ever show movies and TV: music, music videos,
  photos and clips are filtered out.

### Known limits
- Wikidata tags few recent African-American films, so "black films" leans
  toward African cinema for now; a hosted TMDB-backed source is planned.
- Wikidata may throttle during busy periods; the app keeps the normal hero and
  asks you to try again in a minute.

## 0.6.0 — 2026-11-09

### Added
- Keyboard / D-pad paging for the hero carousel (Left on *Watch*, Right on
  *Details*), plus on-screen ‹ › arrows for mouse users (BlueStacks).
- **Trakt lists (advanced, opt-in).** Settings › Trakt lists takes your own
  Trakt Client ID and up to five public list or watchlist URLs; each becomes a
  row with full artwork. Lists are cached for offline use; failures show a
  card naming the list that broke.
- Settings header shows the version on the right.

### Fixed
- **Plex link didn't stick.** Polling stopped when the link dialog closed,
  which happens when you switch to a browser to enter the code. The PIN is now
  persisted and polled on its own thread, the account is saved the moment it's
  approved, and an unreachable server is retried instead of discarding the link.
- Plex errors show a card with Retry instead of a short toast.
- PIN polling no longer blocks catalog refreshes.
- `app/build.gradle` was missing from the repository, so source builds failed.

## 0.5.0 — 2026-11-08
First public release.

### Added
- **Plex integration.** Link with a 4-character code at plex.tv/link (no password
  in the app). Adds *Continue watching* (with resume progress) and *Recently
  added* rows, and Plex items in the hero carousel.
- **Android TV-style app tiles** for phone apps: banners generated from each
  app's adaptive icon, or any image you pick (long-press › *Choose tile image*).
- **Play titles with** setting: ask each time, Stremio, or Plex. Details sheet
  offers every installed option plus a trailer.
- Version number in the footer and Settings; Ko-fi link.

### Changed
- Codebase split into focused classes (`AppRepository`, `CatalogRepository`,
  `PlexClient`, `ImageLoader`, `TileArt`, …) with strings moved to resources.
- App renamed to **Phone TV**. Existing installs update in place and keep
  their default-Home setting.

## 0.4.0
- *Your apps* row moved under the hero, Google TV style.
- System-bar toggle now also hides the navigation buttons.
- Lower heat: lazy image loading, downsized disk cache, background-priority threads,
  no full rebuild when returning to Home.

## 0.3.0
- Detects Android TV (leanback) editions of apps and shows their TV banners.
- Favorites with reorder/remove, Display size setting, auto-hiding top bar.

## 0.2.0
- Google TV-style hero carousel and poster rows.

## 0.1.0
- Initial launcher: HOME intent, app grid, portrait + landscape.
