# Arranging a drive for NEKPLIK

NEKPLIK reads any USB drive or hard disk plugged into the TV. Put a **Movies** folder and a **Series** folder at the top of the drive, with **one folder per movie or series** in them. NEKPLIK finds them when the drive is plugged in, and they go when it's taken out.

```
Movies/
  Inception/
    movie.mkv            the video
    poster.jpg           the picture in the rows
    background.jpg       the big picture behind the details
    info.json            title, description and more
    movie.en.srt         subtitles (optional)
  Arrival (2016)/
    ...
Series/
  The Office/
    poster.jpg
    background.jpg
    info.json
    Season 1/
      01 Pilot.mp4
      01 Pilot.json      this episode's title and description (optional)
      01 Pilot.jpg       this episode's picture (optional)
      01 Pilot.en.srt    subtitles (optional)
      02 Diversity Day.mkv
    Season 2/
      Episode 1/         or a folder per episode, with its own files:
        video.mkv          the episode (any name)
        info.json          its title and description (optional)
        background.jpg     its picture (optional)
        video.en.srt       subtitles (optional)
      Episode 2/
        ...
```

Movies on a computer? Share the folder with `share.py` (see the [README](../README.md#share-from-a-computer)): the same layout, played over the Wi-Fi.

No USB drive? A **Nekplik** folder on the TV's own storage works too (`Nekplik/Movies/...`, `Nekplik/Series/...`): upload into it from Telebisyon's Files page.

## Movies

| File | What it is |
|---|---|
| **The video** | Any name. `.mp4`, `.mkv`, `.webm`, `.mov`, `.ts`, `.m2ts`, `.mpg`, `.wmv`, `.flv`, `.3gp` (and `.avi`, after `avi2mkv.sh`, below). If there's more than one (a trailer, say), the biggest is the movie. |
| `poster.jpg` | The picture in the rows. Portrait, 2:3, about 600×900. Also found as `thumbnail`, `thumb`, `cover` or `folder`, and as `.png` or `.webp`. |
| `background.jpg` | The big picture behind the title. Landscape, 16:9, about 1920×1080. Also found as `backdrop`, `fanart` or `landscape`. |
| `info.json` | About the movie (below). Without one, the folder's name is the title: `Arrival (2016)` reads as *Arrival*, 2016. |
| `<video name>.srt` | Subtitles, named after the video: `movie.srt`, `movie.en.srt`, `movie.fil.srt`, `movie.English.srt`. In a folder with only one video, any `.srt` in it is used, whatever its name. The language comes from the name (`.en.`, `.fil.`, `.English.`), and the TV's language is picked first. |

## Collections

Films that go together, like a franchise, become a collection two ways:

- **`"collection"` in each film's `info.json`**, the same name on each: `"collection": "Fast & Furious"`. Their folders can stay where they are.
- **Or a folder of film folders** in Movies: `Movies/Fast & Furious Collection/Fast Five/…`. The folder's name is the collection's, without a last word like *Collection* or *Saga*.

A collection with two films or more gets its own row on the main screen, in release order (by `year`), and each film's details show the rest of it.

## Series

The same files in the series' folder (`poster.jpg`, `background.jpg`, `info.json`), and a **folder per season** with the episodes in it. Seasons and episodes go in name order, with numbers read as numbers (`Season 2` before `Season 10`), so start names with the number: `01 Pilot.mp4`, `02 Diversity Day.mkv`. If a series has just one season, the episodes can go straight in the series' folder.

An episode's name is its file name, without a number in front (`01 Pilot` and `S01E01 - Pilot` read *Pilot*). For more, put a `.json` with the same name next to it:

```json
{ "title": "Pilot", "description": "Michael shows a documentary crew around the office." }
```

and a `.jpg` with the same name for its picture (else the series' background).

**Or a folder per episode**, inside the season's folder: `Season 1/Episode 1/`, with the video (any name), and, if you like, an `info.json` with the same `title` and `description`, a picture (`background.jpg`, `thumb.jpg` or `poster.jpg`; a wide one looks best) and `.srt` subtitles. Without an `info.json`, the folder's name is the episode's (`02 - The Blind Banker` reads *The Blind Banker*), unless it's only `Episode 2`: then the video's name is. Both ways can be mixed in a season.

Episodes go by the number in their name, however it's written: `Episode 2`, `E02`, `S01E02`, `1x02` or `02 Pilot`.

## info.json

Only **title** and **description** are needed. The rest makes the screens richer: the year, rating, runtime and score show under the title, and **genres** gives the main screen its genre rows.

```json
{
  "title": "Inception",
  "description": "A thief who steals corporate secrets through dreams is given one last job.",
  "year": 2010,
  "released": "2010-07-16",
  "rating": "PG-13",
  "score": 8.8,
  "runtime": 148,
  "genres": ["Sci-Fi", "Thriller"],
  "cast": ["Leonardo DiCaprio", "Joseph Gordon-Levitt", "Elliot Page"],
  "director": "Christopher Nolan",
  "language": "English",
  "tagline": "Your mind is the scene of the crime."
}
```

| Field | | What it does |
|---|---|---|
| `title` | **needed** | The name everywhere. |
| `description` | **needed** | Under the title, on the main screen and the details. |
| `year` | optional | Shown under the title. Taken from `released` if missing. |
| `released` | optional | The release date, `YYYY-MM-DD`. |
| `rating` | optional | The age rating: `PG-13`, `TV-MA`, `18`… |
| `score` | optional | Shown as ★ 8.8. |
| `runtime` | optional | In minutes: shown as 2h 28m. For movies. |
| `genres` | optional | A list, or one string with commas. Genres with three titles or more get a row: up to 10 of them, picked and ordered at random (and their titles shuffled) each time NEKPLIK or the page opens. |
| `cast` | optional | A list. The first five show as *Starring*. |
| `director` | optional | Shown on the details. |
| `language` | optional | For your own reference. |
| `tagline` | optional | A line in red above the description. |
| `collection` | optional | The films it goes with, the same name on each: `"Fast & Furious"`. They get a row of their own on the main screen, in release order, and each film's details show **More in Fast & Furious**. Films without one show **More like this** (others sharing their genres). |

Save it as UTF-8 (any editor's default). A mistake in it (a missing comma, say) doesn't hide the movie: it shows with its folder's name, and Telebisyon's NEKPLIK page says what's wrong.

## Subtitles

NEKPLIK finds subtitles by itself, as above. While a video plays, **▼** goes through them: off, then each language, then off again ("Subtitles: English", "Subtitles: Filipino"). The choice is remembered: turned off, they stay off for the next videos; with a language picked, that one comes first next time. Only `.srt` subtitles are read (UTF-8, or Windows' Western encoding).

## What plays

NEKPLIK plays with the TV's own video player, so it plays what the TV's hardware can: most **MP4** and **MKV** files with **H.264** or **H.265 (HEVC)** video and AAC, AC3 or MP3 sound. If a file won't play, NEKPLIK says so: convert it to MP4 with H.264 video and AAC sound (HandBrake's "Fast 1080p30" preset does).

**AVI files** don't play: Android TVs can't read the AVI file itself, though they decode the XviD or DivX video and MP3 or AC3 sound inside. `avi2mkv.sh` copies them into MKV files without re-encoding, so there's no loss of quality, and it takes a minute or two a film. It needs ffmpeg (`brew install ffmpeg` on a Mac):

```
./avi2mkv.sh /Volumes/Movies             # each AVI is moved to "AVI originals" on the drive
./avi2mkv.sh /Volumes/Movies --delete    # or deleted, once its MKV checks out
```
