# RetroFire

RetroArch for the Fire TV, set up from a USB stick: plug the stick in, open the app, play.
A fork of [libretro/RetroArch](https://github.com/libretro/RetroArch) v1.22.2, branch `retrofire`.

Rules for the patches:
- small, in the style of the upstream code, easy to rebase on a new RetroArch release;
- every new behaviour is switched on by the config; with the defaults the app behaves as
  upstream RetroArch;
- the only exception: an external disk with `RetroFire/retroarch.cfg` (see below) is used at
  start-up.

## Install

The APK is `com.retroarch.retrofire` ("RetroFire"). It sits next to the Appstore RetroArch
(`com.retroarch`), which can stay or go.

1. On the Fire TV: Settings → My Fire TV → Developer options → ADB debugging on.
2. Download `RetroFire.apk` from the release
   [build-retrofire](https://github.com/mefistofelix/RetroArch/releases/tag/build-retrofire)
   and install it: `adb install -r RetroFire.apk` (the RetroFire PC tools do this).
3. Open RetroFire once and allow access to files.
4. Plug in the stick prepared as below and open RetroFire again.

## The USB stick

```
<stick>/RetroFire/              read only for the app, never deleted by Android
  retroarch.cfg                 the config (template, see below)
  cores/                        *.so and *.info: copied to the app at start-up
  roms/ bios/ playlists/ thumbnails/ autoconfig/ ...
<stick>/Android/data/com.retroarch.retrofire/files/
                                written by the app: saves, states, history, screenshots
                                (Android lets an app write only here on a USB stick;
                                uninstalling the app deletes this folder)
```

### External config

At start-up the app looks at every external disk and takes the first one with
`RetroFire/retroarch.cfg`. That file is a template: its keys are written over the app's own
`retroarch.cfg` at every start; keys it does not set keep the value the user chose in the menu.
Tokens expanded in the values:

| Token | Becomes |
|---|---|
| `{cfgdir}` | the folder of the external config, e.g. `/storage/988E-E016/RetroFire` |
| `{appdata}` | the app's writable folder on the same disk, `/storage/<id>/Android/data/com.retroarch.retrofire/files` |
| `{appdir}` | the app's internal folder, `/data/user/0/com.retroarch.retrofire` |

So the config never contains the stick's serial number. Example:

```
system_directory = "{cfgdir}/bios"
playlist_directory = "{cfgdir}/playlists"
savefile_directory = "{appdata}/saves"
content_history_path = "{appdata}/history/content_history.lpl"
libretro_directory = "{appdir}/cores"
```

Playlists: with `playlist_portable_paths = "true"` and `rgui_browser_directory = "{cfgdir}"`,
RetroArch rewrites the playlist entries from the playlist's `base_content_directory` to the
current stick (upstream feature, nothing patched).

### Cores

Android does not run code from a USB stick. The `.so` files in `RetroFire/cores` are copied
to the app's internal `cores` folder (the `.info` files to `info`) at start-up when new or
changed (size or date). Nothing is deleted. No Core Downloader needed.

## Input

| Option | Values | What it does |
|---|---|---|
| `materialui_thumbnail_view_landscape` / `_portrait` | `5` / `4` = Grid (new) | MaterialUI playlists as a grid of pictures, see below |
| `materialui_grid_show_time` | ms, default `6000` | Grid: how long each picture stays |
| `materialui_grid_fade_time` | ms, default `2000` | Grid: how long the cross-fade between the two pictures takes |
| `menu_startup_playlist` | playlist file name, e.g. `Giochi.lpl` (new, default empty) | open that playlist when the app starts (Back goes to the usual menu) |
| `playlist_recent_first` | `true` / `false` (new, default `false`) | in playlists, content that is in the history comes first, most recent first |
| `input_android_physical_keyboard` | `auto` (new) | every full (alphabetic) keyboard is a keyboard, whatever its name; gamepads and remotes are not. The upstream values (one device name, or `vvvv:pppp Name`) still work |

## Development status

| Feature | Status |
|---|---|
| Actions build: only the Fire TV APK (armeabi-v7a), assets bundled, rolling release | done |
| Stable signing key (in-place updates) | done: secrets `RELEASE_KEYSTORE_B64` / `RELEASE_KEYSTORE_PASSWORD`, APK signed `CN=RetroFire` |
| External config + tokens | works on the Fire TV 4K Max (8 Nov 2026) |
| Cores copied from the stick | works: 12 cores in about 5 s on the first start, a game starts with them |
| Keyboard `auto` | works: the Logitech USB keyboard drives the menu, no "fallback" pad |
| Grid view (`materialui_thumbnail_view_landscape = "5"`, portrait `"4"`): cells, box art and screenshot cross-fading, title and info over the picture, D-pad between cells | works on the Fire TV; text size and the info line to tune |
| Start on a playlist (`menu_startup_playlist`), recently played first (`playlist_recent_first`) | written, to test |
| Fire TV remote no longer resets the pad ports when it is used | to do |
| Device reservation: a pad moved to its reserved port loses its buttons | to investigate |
| RetroFire PC tools (`rf install`, `rf cores`, `rf push`) write the stick layout and install the APK | done, in use on the Fire TV 4K Max since 8 Nov 2026 |

## To do later

- **Games on demand.** A playlist that points to a URL (`playlist url = ...`) with all the
  metadata: game list, files, the core and BIOS each game needs. A game, its core and BIOS
  are downloaded when first started, kept in a local cache, and removed (after asking) when
  the cache is full or unused.

## Build

Push to `retrofire` (or run the workflow by hand): `.github/workflows/retrofire.yml` builds
the `retrofire` flavor (`./gradlew assembleRetrofireRelease`) and replaces the pre-release
`build-retrofire` with `RetroFire.apk`, the unstripped `libretroarch-activity.debug.so` and
`SHA256SUMS.txt`. Upstream workflows are removed on this branch.
