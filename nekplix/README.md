<div align="center">

<img src="res/drawable-xhdpi/banner.png" alt="NEKPLIX" width="320">

### Your own movies and series, from a USB drive, on the TV.

</div>

NEKPLIX is an optional TV app that shows the movies and series on a USB drive, a hard disk or a computer on the same Wi-Fi the way a streaming app does: the highlighted title big at the top, with rows of posters under it. Plug a drive in and it fills up by itself; take it out and they go.

- **Rows:** Continue watching, Recently added, Movies, Series, a row per collection (Fast & Furious), and a row per genre.
- **Search:** at the top right. Type with the arrows on an on-screen keyboard (or speak, if the TV has voice input), and the titles that match show as you go: by name, collection, genre, actor, director or year.
- **Details:** the background, title, year, rating, runtime, score, description, cast and director. Movies have **Play**, or **Resume** and **Start over**. Series pick up where you were, with seasons and episodes underneath.
- **The player:** resume where you stopped, subtitles from `.srt` files or inside the video (in more than one language if you have them), and the next episode starts by itself.
- **On Telebisyon:** NEKPLIX joins the apps row on the home screen (and on the web page's copy of it), and the web page gets a **NEKPLIX library** tile: a page listing everything found on the drives, and what needs fixing (no poster, a broken `info.json`…).

It's a separate app from Telebisyon, built and installed on its own.

## Install

On a computer on the same Wi-Fi as the TV, with debugging on (as for Telebisyon):

```sh
sh nekplix.sh <TV_IP>          # installs or updates it
sh nekplix.sh <TV_IP> off      # uninstalls it
```

It builds NEKPLIX if the computer can (Java 17 and the Android SDK, see `build.sh`), or downloads the latest release. It also lets NEKPLIX read drives: TVs have no screen to ask for that.

It also puts NEKPLIX at the end of the apps row on Telebisyon's home screen, if Telebisyon is installed (the rest of the row stays as it is). Otherwise, open it from the TV's apps.

## Share from a computer

Movies can stay on your laptop, or on a hard disk plugged into it: NEKPLIX plays them over the Wi-Fi, next to what's on USB drives.

**Mac: the NEKPLIX Share app.** Build it once, in `share-app/`: `sh make-mac-app.sh` makes **NEKPLIX Share.app** (drag it to Applications if you like). Then just double-click it: choose the folder with your movies (a hard disk is under **Locations**, or `/Volumes/<its name>`), and it shares them until you click **Stop sharing**. Next time, it offers the same folder again.

**Windows: NEKPLIX Share.bat** in `share-app/`. Double-click it, choose the folder, and it shares until you close its window. It needs [Python 3](https://www.python.org/downloads/) (tick *Add python.exe to PATH* when installing); allow *Private networks* when Windows asks.

**Or from a terminal**, on any computer, in this folder:

```sh
python3 share.py ~/Movies                # the folder with Movies and Series in it (or one of them)
python3 share.py ~/Movies --name "Den"   # the name the TV shows (default: the computer's name)
```

NEKPLIX finds it by itself (it looks every 30 seconds while it's open) and shows it as a source next to the drives, with the same folder layout. Keep it running while you watch; **Ctrl+C** stops sharing.

- **Nothing to install:** standard Python 3. On a Mac without it, `python3` offers to install Apple's free Command Line Tools the first time.
- **Only that folder is shared**, read-only, and every request needs a code only NEKPLIX is told (kept in `~/.nekplix-share`), so others on the Wi-Fi can't browse it.
- **Skipping around is instant:** the TV asks for just the part it needs.
- **On a Mac, it keeps the computer awake while a video plays**, and lets it sleep otherwise. On battery, a long movie uses about as much as leaving the Mac idle with its screen off.
- If macOS asks to **allow incoming connections** for Python, click **Allow**, or the TV can't reach it.
- It uses ports 8765 (the files) and 8766 (NEKPLIX finding it). Windows and Linux work too; there, keep the computer from sleeping yourself.

## Arrange the drive

A **Movies** and a **Series** folder at the top of the drive, one folder per movie or series:

```
Movies/Inception/movie.mkv, poster.jpg, background.jpg, info.json
Series/The Office/poster.jpg, background.jpg, info.json, Season 1/01 Pilot.mp4
```

`info.json` needs only a **title** and a **description**: year, release date, rating, score, runtime, genres, cast, director, language and tagline are optional. Everything is in [docs/library.md](docs/library.md).

## On the TV

| Where | Keys |
|---|---|
| The rows | Arrows to move, **OK** to open. |
| Details | **OK** on Play or Resume. Series: down to the seasons and episodes. |
| Playing | **OK** pauses, **◀ ▶** skip 10 seconds (further while held), rewind and fast forward skip 30, **▼** goes through the subtitles (off, then each language; your choice is remembered for the next video), **Back** stops (and remembers where). |

## Build it yourself

```sh
sh build.sh        # out/nekplix.apk
```

Plain Java, no libraries, no Gradle: Java 17 and the Android SDK (platform 34, build-tools 34.0.0). `art.py` redraws the icon and banner.

**Signing:** inside the Telebisyon folder, it's signed with Telebisyon's `key.jks`; on its own, with a `key.jks` of its own made on the first build. Telebisyon's NEKPLIX page only works when both apps have the **same key**: set `KEYSTORE` and `KEYSTORE_PASS` to Telebisyon's to keep it working after moving this folder out.

## Credits

NEKPLIX is part of [Telebisyon](../README.md) by knz. MIT License.
