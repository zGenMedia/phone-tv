# Changelog

All notable changes to Phone TV. Versions follow `MAJOR.MINOR.PATCH`; the
version is shown in the app's footer and at the top of Settings.

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
