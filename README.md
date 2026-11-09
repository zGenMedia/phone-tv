<div align="center">

# Phone TV

**Android TV on mobile devices, finally.**

A free, open-source, TV-style home screen that turns any Android phone — especially the old one in your drawer — into a streaming box. Runs on Windows PCs too, through BlueStacks, for a clean lean-back screen on the big TV.

[**Download the latest APK**](https://github.com/zGenMedia/phone-tv/releases/latest) · [Website](https://zgenmedia.github.io/phone-tv/) · [Support on Ko-fi](https://ko-fi.com/zgenmedia/tiers) · [zGenMedia](https://zgenmedia.com)

![Build](https://github.com/zGenMedia/phone-tv/actions/workflows/build.yml/badge.svg)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3ddc84)
![APK size](https://img.shields.io/badge/APK-~70%20KB-informational)
![License: MIT](https://img.shields.io/badge/license-MIT-blue)

</div>

---

<p align="center">
  <img src="docs/assets/screens/pc-bluestacks-landscape.jpg" width="68%" alt="Phone TV in landscape on a Windows PC via BlueStacks">
  &nbsp;
  <img src="docs/assets/screens/phone-portrait.jpg" width="22%" alt="Phone TV in portrait on an Android phone">
</p>
<p align="center"><sub>Real screenshots: Windows PC via BlueStacks (left) and an Android phone in portrait (right).</sub></p>

## Why

Plenty of us have a 2–6 year old phone that still has a great screen, Wi-Fi, HDMI-over-USB-C or Cast, and enough power to stream 1080p. What it doesn't have is a living-room interface. Phone TV gives it one: a lean-back home screen with a featured carousel, a shelf of your streaming apps, and rows of what's on — closer to a Shield-style TV box than a phone launcher.

## Features

| | |
|---|---|
| **TV home screen** | Hero carousel, *Your apps* shelf, Movies / Shows tabs, auto-hiding top bar |
| **Runs Android TV apps** | Detects TV (leanback) editions of apps you've sideloaded and launches them correctly |
| **TV-style tiles** | Real Android TV banners when an app ships one; otherwise a banner built from the app's adaptive icon — or any image you choose |
| **Plex** | Link with a 4-character code at plex.tv/link. *Continue watching* with progress bars, *Recently added* |
| **Your lists (optional)** | Power users can add public Trakt lists (e.g. Black cinema, Nollywood, anime) as extra rows with their own Trakt Client ID. Off by default |
| **Keyboard & remote friendly** | Arrow keys page the hero carousel; D-pad focus everywhere; on-screen arrows for mouse users |
| **Your choice of player** | Open titles in Stremio, search them in Plex, or watch the trailer |
| **Made for old hardware** | No dependencies, ~70 KB APK, lazy image loading, downsized disk cache, background-priority threads |
| **Fullscreen** | Hides status and navigation bars; portrait and landscape layouts |
| **Private** | No accounts, analytics or ads. Network calls: the public catalog, image CDN, and your Plex server if you link it |

## Install

1. Download `PhoneTV.apk` from [Releases](https://github.com/zGenMedia/phone-tv/releases/latest) on the phone.
2. Open it and allow *Install unknown apps* for your browser or file manager.
   On Samsung, temporarily turn off **Settings › Security and privacy › Auto Blocker** if it blocks the install.
3. Press **Home** and choose **Phone TV**, or open it and go to **Settings › Make Phone TV my Home screen**.

Updates install over the top — your favorites and settings are kept.

Prefer the command line?

```sh
adb install -r PhoneTV.apk
adb shell cmd package set-home-activity com.kinglifer.tvlauncher/com.kinglifer.tvlauncher.MainActivity
```

## Turning an old phone into a TV box

- **Big screen:** USB-C to HDMI (phones with DisplayPort Alt Mode), MHL on older models, or Cast / Smart View / Miracast.
- **Control:** any Bluetooth keyboard, game controller or air-mouse remote. Phone TV supports D-pad focus.
- **Power:** keep it plugged in; enable your phone's battery-protect / 85 % charge limit if it has one.
- **Apps:** install TV editions where they exist — Phone TV puts them on the shelf with their TV banners.

## On a Windows PC (BlueStacks)

1. Install the free [BlueStacks](https://www.bluestacks.com/) player.
2. Drag `PhoneTV.apk` onto the BlueStacks window, or use its *Install APK* button.
3. Open Phone TV and switch BlueStacks to fullscreen. Mouse and keyboard work out of the box; BlueStacks also supports game controllers.

Plug the PC into a TV and you have a lean-back home screen without buying a streaming box.

## Building from source

Requirements: JDK 17, Gradle 8.14+ and the Android SDK (platform 36).

```sh
git clone https://github.com/zGenMedia/phone-tv.git
cd phone-tv
gradle assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

Without a `keystore.properties` file the build is signed with the standard debug key, which is fine for sideloading. Release signing reads `keystore.properties` from the project root:

```properties
storeFile=keystore/your-key.jks
storePassword=…
keyAlias=…
keyPassword=…
```

### Project layout

```
app/src/main/java/com/zgenmedia/tvhome/
  HomeActivity.java       screen composition, dialogs, actions
  AppRepository.java      phone + Android TV app discovery, launching, favorites
  TileArt.java            TV banners, adaptive-icon banners, custom tile images
  CatalogRepository.java  public movie/show catalog with offline cache
  PlexClient.java         plex.tv PIN linking, server discovery, library rows
  ImageLoader.java        downsampling memory + disk image cache, lazy loading
  MediaItem.java          one title from either source
  Prefs.java, Ui.java, Http.java
```

Views are built in code (no XML layouts, no androidx) so the whole screen rebuilds in one pass on rotation or size changes and the APK stays tiny.

## FAQ

**Is this the real Android TV / Google TV?**
No. Google's TV software only runs on certified TV hardware. Phone TV is an independent launcher that recreates the experience on a phone.

**Does it run on PC?**
Yes, inside BlueStacks on Windows. See [On a Windows PC](#on-a-windows-pc-bluestacks).

**Will TV-only apps work?**
Most TV editions install and launch. Some expect a remote (use a Bluetooth controller) or lock to landscape.

**Where do the movie and show rows come from?**
The open Cinemeta catalog (the same metadata Stremio uses). No API key, no account. Titles open in whichever app you choose.

**Does it collect data?**
No analytics, no tracking. Plex tokens are stored only on the device and excluded from backups.

## Contributing

Issues and pull requests are welcome. Keep changes dependency-free where possible and test on a low-end device if you can — that's who this is for.

## Support

Phone TV is free and always will be. If it gave an old phone a second life, you can [buy zGenMedia a coffee on Ko-fi](https://ko-fi.com/zgenmedia/tiers).

---

Made by [zGenMedia](https://zgenmedia.com) · MIT License

<sub>Phone TV is an independent project and is not affiliated with or endorsed by Google, NVIDIA, Plex or Stremio. Android and Android TV are trademarks of Google LLC. Movie and show metadata and artwork are provided by third-party services and belong to their respective owners.</sub>
