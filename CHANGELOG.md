# Changelog

All notable changes to Phone TV. Versions follow `MAJOR.MINOR.PATCH`; the
version is shown in the app's footer and at the top of Settings.

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
