package com.knz.nekplix;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// What's on the connected drives: every USB drive or hard disk, a Nekplix folder on the TV's own storage,
// and computers on the Wi-Fi sharing their movies with share.py (Shares). On each, a Movies folder with one folder per movie, and a Series folder with one folder per
// series and a folder per season in it (see docs/library.md):
//
//   Movies/Inception/movie.mkv, poster.jpg, background.jpg, info.json, movie.en.srt
//   Series/The Office/poster.jpg, background.jpg, info.json, Season 1/01 Pilot.mp4, 01 Pilot.json
//
// info.json needs only a title and a description; the rest is optional. Without one, the folder's name
// is the title. Whatever looks wrong goes in a title's problems, for the web page to show.
final class Library {
    static final List<String> VIDEO = Arrays.asList("mp4", "m4v", "mkv", "webm", "avi", "mov", "ts", "m2ts", "mpg", "mpeg", "wmv", "flv", "3gp");
    private static final List<String> IMAGE = Arrays.asList("jpg", "jpeg", "png", "webp");
    private static final List<String> POSTER = Arrays.asList("poster", "thumbnail", "thumb", "cover", "folder");
    private static final List<String> BACKGROUND = Arrays.asList("background", "backdrop", "fanart", "landscape");

    static final class Drive {
        String name;
        Node root;
        boolean share; // a computer, not a drive in the TV
    }

    static final class Video {
        Node file;
        String title, description;
        Node thumb;
        List<Node> subtitles = new ArrayList<>();
        int season, number; // for episodes: which season (from 1) and which episode in it (from 1)
    }

    static final class Season {
        String name;
        List<Video> episodes = new ArrayList<>();
    }

    static final class Title {
        boolean series;
        Node folder;
        String title, description, tagline, rating, released, director, language;
        String collection; // the films it goes with: "Fast & Furious" (info.json, or a collection folder)
        int year, runtime; // runtime in minutes
        double score;
        List<String> genres = new ArrayList<>(), cast = new ArrayList<>(), problems = new ArrayList<>();
        Node poster, background;
        Video movie;
        List<Season> seasons = new ArrayList<>();
        Drive drive;

        // Every episode, season after season, for playing on into the next one.
        List<Video> episodes() {
            List<Video> all = new ArrayList<>();
            for (Season s : seasons) all.addAll(s.episodes);
            return all;
        }

        // "2010 · PG-13 · 2h 28m · ★ 8.8", or "3 seasons" for a series, from what's known. With more than
        // one drive or computer, also where it is: "· on Den MacBook".
        String meta() {
            List<String> bits = new ArrayList<>();
            if (year > 0) bits.add(String.valueOf(year));
            if (rating != null) bits.add(rating);
            if (series) bits.add(seasons.size() == 1 ? episodes().size() + " episodes" : seasons.size() + " seasons");
            else if (runtime > 0) bits.add(runtime >= 60 ? runtime / 60 + "h " + runtime % 60 + "m" : runtime + "m");
            if (score > 0) bits.add("★ " + (score == Math.floor(score) ? String.valueOf((int) score) : String.valueOf(score)));
            if (!genres.isEmpty()) bits.add(join(genres.subList(0, Math.min(3, genres.size()))));
            Library lib = current;
            if (lib != null && lib.drives.size() > 1 && drive != null) bits.add("on " + drive.name);
            return android.text.TextUtils.join("  ·  ", bits);
        }
    }

    final List<Drive> drives = new ArrayList<>();
    final List<Title> movies = new ArrayList<>(), series = new ArrayList<>();

    // The last scan, shared by the screens in this app. Scanning reads the drives, so it's done off
    // the main thread.
    static volatile Library current;

    static Library scan(Context c) {
        Library lib = new Library();
        List<Drive> all = drives(c);
        all.addAll(shares());
        for (Drive d : all) {
            boolean found = false;
            for (Node f : d.root.list()) {
                if (!f.isDirectory()) continue;
                String n = f.name().toLowerCase(Locale.ROOT);
                if (n.equals("movies") || n.equals("films")) { lib.readMovies(f, d); found = true; }
                else if (n.equals("series") || n.equals("tv shows") || n.equals("shows")) { lib.readSeries(f, d); found = true; }
            }
            if (found) lib.drives.add(d);
        }
        Collections.sort(lib.movies, (a, b) -> natural(a.title, b.title));
        Collections.sort(lib.series, (a, b) -> natural(a.title, b.title));
        current = lib;
        return lib;
    }

    boolean empty() { return movies.isEmpty() && series.isEmpty(); }

    // Whether nekplix.sh let this app read drives: "All files access" from Android 11, else storage.
    static boolean canRead(Context c) {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return Build.VERSION.SDK_INT < 23
                || c.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    // Computers sharing their movies (share.py): asked on the Wi-Fi, then each one's folders.
    static List<Drive> shares() {
        List<Drive> out = new ArrayList<>();
        for (Shares.Share s : Shares.find(1200)) {
            Node.Remote root = Shares.tree(s);
            if (root == null) continue;
            Drive d = new Drive();
            d.name = s.name;
            d.root = root;
            d.share = true;
            out.add(d);
        }
        return out;
    }

    // USB drives and hard disks that are plugged in, and the TV's own Nekplix folder if there is one.
    static List<Drive> drives(Context c) {
        List<Drive> out = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 24) {
            StorageManager sm = (StorageManager) c.getSystemService(Context.STORAGE_SERVICE);
            for (StorageVolume v : sm.getStorageVolumes()) {
                if (!v.isRemovable() || !Environment.MEDIA_MOUNTED.equals(v.getState())) continue;
                File dir = directory(v);
                if (dir == null) continue;
                Drive d = new Drive();
                d.name = v.getDescription(c);
                d.root = new Node.Local(dir);
                out.add(d);
            }
        } else {
            // Before Android 7 there's no list of volumes: whatever is mounted under /storage, but the TV's.
            File[] all = new File("/storage").listFiles();
            String own = Environment.getExternalStorageDirectory().getAbsolutePath();
            if (all != null) for (File f : all) {
                if (f.getName().equals("emulated") || f.getName().equals("self") || own.startsWith(f.getAbsolutePath()) || !f.canRead()) continue;
                Drive d = new Drive();
                d.name = f.getName();
                d.root = new Node.Local(f);
                out.add(d);
            }
        }
        File own = new File(Environment.getExternalStorageDirectory(), "Nekplix");
        if (own.isDirectory()) {
            Drive d = new Drive();
            d.name = "TV storage";
            d.root = new Node.Local(own);
            out.add(d);
        }
        return out;
    }

    private static File directory(StorageVolume v) {
        if (Build.VERSION.SDK_INT >= 30) return v.getDirectory();
        try { return (File) StorageVolume.class.getMethod("getPathFile").invoke(v); }
        catch (Exception e) {
            try { return new File((String) StorageVolume.class.getMethod("getPath").invoke(v)); }
            catch (Exception x) { return null; }
        }
    }

    private void readMovies(Node dir, Drive drive) {
        for (Node f : dir.list()) {
            if (!f.isDirectory() || f.name().startsWith(".")) continue;
            // A folder of film folders (and no video of its own) is a collection: "Fast & Furious Collection".
            boolean video = false, films = false;
            for (Node x : f.list()) { video |= isVideo(x); films |= x.isDirectory() && !x.name().startsWith("."); }
            if (!video && films) {
                String name = f.name().replaceAll("(?i)\\s*[-–:]?\\s*(collection|saga|series|films|movies)\\s*$", "").trim();
                for (Node m : f.list()) if (m.isDirectory() && !m.name().startsWith(".")) readMovie(m, drive, name.isEmpty() ? f.name() : name);
            } else readMovie(f, drive, null);
        }
    }

    private void readMovie(Node f, Drive drive, String collection) {
        Title t = new Title();
        t.folder = f;
        t.drive = drive;
        readInfo(t);
        if (t.collection == null) t.collection = collection;
        Node video = null;
        for (Node x : list(f)) if (isVideo(x) && (video == null || x.length() > video.length())) video = x; // the film, not a trailer
        if (video == null) { t.problems.add("No video file in the folder"); movies.add(t); return; }
        Video v = new Video();
        v.file = video;
        v.title = t.title;
        v.description = t.description;
        v.subtitles = subtitles(video);
        t.movie = v;
        movies.add(t);
    }

    // Collections with two films or more, each in release order: "Fast & Furious" -> its films.
    java.util.Map<String, List<Title>> collections() {
        java.util.Map<String, List<Title>> out = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Title t : movies) if (t.collection != null) {
            List<Title> l = out.get(t.collection);
            if (l == null) out.put(t.collection, l = new ArrayList<>());
            l.add(t);
        }
        for (java.util.Iterator<List<Title>> it = out.values().iterator(); it.hasNext(); ) {
            List<Title> l = it.next();
            if (l.size() < 2) { it.remove(); continue; }
            Collections.sort(l, (a, b) -> a.year != b.year ? Integer.compare(a.year == 0 ? 9999 : a.year, b.year == 0 ? 9999 : b.year) : natural(a.title, b.title));
        }
        return out;
    }

    // For a title's details: the rest of its collection, else up to 12 others sharing its genres, the
    // most shared first, then the best scored.
    List<Title> related(Title t) {
        if (t.collection != null) {
            List<Title> c = collections().get(t.collection);
            if (c != null) { List<Title> out = new ArrayList<>(c); out.remove(t); return out; }
        }
        List<Title> all = new ArrayList<>(movies);
        all.addAll(series);
        all.remove(t);
        List<Title> out = new ArrayList<>();
        for (Title o : all) if (shared(t, o) > 0) out.add(o);
        Collections.sort(out, (a, b) -> shared(t, b) != shared(t, a) ? shared(t, b) - shared(t, a) : Double.compare(b.score, a.score));
        return out.subList(0, Math.min(12, out.size()));
    }

    private static int shared(Title a, Title b) {
        int n = 0;
        for (String g : a.genres) for (String h : b.genres) if (g.equalsIgnoreCase(h)) n++;
        return n;
    }

    private void readSeries(Node dir, Drive drive) {
        for (Node f : dir.list()) {
            if (!f.isDirectory() || f.name().startsWith(".")) continue;
            Title t = new Title();
            t.series = true;
            t.folder = f;
            t.drive = drive;
            readInfo(t);
            // A folder per season, or the episodes straight in the series' folder for just one.
            List<Node> seasonDirs = new ArrayList<>();
            for (Node x : list(f)) if (x.isDirectory() && !x.name().startsWith(".")) seasonDirs.add(x);
            Season loose = episodes(f, "Episodes", 1);
            if (loose != null) t.seasons.add(loose);
            for (Node s : seasonDirs) {
                Season season = episodes(s, s.name(), t.seasons.size() + 1);
                if (season != null) t.seasons.add(season);
            }
            if (t.seasons.isEmpty()) t.problems.add("No episodes: put video files in a folder per season");
            series.add(t);
        }
    }

    private static Season episodes(Node dir, String name, int number) {
        List<Node> videos = new ArrayList<>();
        for (Node x : list(dir)) if (isVideo(x)) videos.add(x);
        if (videos.isEmpty()) return null;
        Season s = new Season();
        s.name = name;
        for (Node file : videos) {
            Video v = new Video();
            v.file = file;
            v.season = number;
            v.number = s.episodes.size() + 1;
            String base = base(file);
            v.title = cleanEpisode(base);
            Node json = sibling(file, base, Collections.singletonList("json"));
            if (json != null) {
                try {
                    JSONObject j = new JSONObject(read(json));
                    if (!j.optString("title").trim().isEmpty()) v.title = j.optString("title").trim();
                    v.description = emptyToNull(j.optString("description"));
                } catch (Exception ignored) {}
            }
            v.thumb = sibling(file, base, IMAGE);
            v.subtitles = subtitles(file);
            s.episodes.add(v);
        }
        return s;
    }

    // info.json, the poster and the background. The folder's name is the title until info.json says
    // otherwise, with a year in brackets taken off: "Inception (2010)".
    private static void readInfo(Title t) {
        Matcher m = Pattern.compile("^(.*?)\\s*[(\\[](\\d{4})[)\\]]\\s*$").matcher(t.folder.name());
        if (m.matches()) { t.title = m.group(1); t.year = Integer.parseInt(m.group(2)); }
        else t.title = t.folder.name();
        for (Node f : t.folder.list()) {
            String base = base(f).toLowerCase(Locale.ROOT), ext = ext(f);
            if (!IMAGE.contains(ext)) continue;
            if (POSTER.contains(base) && t.poster == null) t.poster = f;
            else if (BACKGROUND.contains(base) && t.background == null) t.background = f;
        }
        Node info = t.folder.child("info.json");
        if (info == null || !info.isFile()) {
            t.problems.add("No info.json (using the folder name as the title)");
        } else {
            try {
                JSONObject j = new JSONObject(read(info));
                String title = j.optString("title").trim();
                if (!title.isEmpty()) t.title = title;
                else t.problems.add("info.json has no title");
                t.description = emptyToNull(j.optString("description"));
                if (t.description == null) t.problems.add("info.json has no description");
                t.tagline = emptyToNull(j.optString("tagline"));
                t.rating = emptyToNull(j.optString("rating"));
                t.released = emptyToNull(j.optString("released"));
                t.director = emptyToNull(j.optString("director"));
                t.language = emptyToNull(j.optString("language"));
                if (j.has("year")) t.year = j.optInt("year", t.year);
                if (t.year == 0 && t.released != null && t.released.matches("^\\d{4}.*")) t.year = Integer.parseInt(t.released.substring(0, 4));
                t.runtime = j.optInt("runtime", 0);
                t.score = j.optDouble("score", 0);
                if (Double.isNaN(t.score)) t.score = 0;
                t.genres = strings(j.opt("genres"));
                t.cast = strings(j.opt("cast"));
                t.collection = emptyToNull(j.optString("collection"));
            } catch (Exception e) {
                t.problems.add("info.json can't be read: " + e.getMessage());
            }
        }
        if (t.poster == null) t.problems.add("No poster (poster.jpg)");
        if (t.background == null) t.problems.add("No background (background.jpg)");
    }

    // ["Sci-Fi", "Drama"], or one string with commas: "Sci-Fi, Drama".
    private static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) if (!a.optString(i).trim().isEmpty()) out.add(a.optString(i).trim());
        } else if (o instanceof String) {
            for (String s : ((String) o).split(",")) if (!s.trim().isEmpty()) out.add(s.trim());
        }
        return out;
    }

    // Subtitles for a video: .srt files named after it (movie.srt, movie.en.srt, movie.English.srt), or,
    // in a folder with just this one video, any .srt there, whatever its name. The TV's language first.
    private static List<Node> subtitles(Node video) {
        List<Node> named = new ArrayList<>(), any = new ArrayList<>();
        String base = base(video).toLowerCase(Locale.ROOT);
        int videos = 0;
        for (Node f : list(video.parent())) {
            String n = f.name().toLowerCase(Locale.ROOT);
            if (isVideo(f)) videos++;
            if (!n.endsWith(".srt") || n.startsWith(".")) continue;
            any.add(f);
            if (n.startsWith(base)) named.add(f);
        }
        List<Node> out = named.isEmpty() && videos == 1 ? any : named;
        String mine = Locale.getDefault().getDisplayLanguage();
        List<Node> first = new ArrayList<>();
        for (Node f : out) if (mine.equals(language(f))) first.add(f);
        out.removeAll(first);
        first.addAll(out);
        return first;
    }

    // The language a subtitle file's name says, in the TV's language: "movie.en.srt" or
    // "movie.English.srt" reads English. Null when the name doesn't say ("Inception.2010.1080p.srt").
    static String language(Node srt) {
        String b = base(srt);
        int dot = b.lastIndexOf('.');
        if (dot < 0) return null;
        String tag = b.substring(dot + 1).trim();
        if (tag.matches("[A-Za-z]{2,3}([-_][A-Za-z]{2})?")) {
            Locale l = Locale.forLanguageTag(tag.replace('_', '-'));
            String name = l.getDisplayLanguage();
            if (!name.isEmpty() && !name.equalsIgnoreCase(tag)) return name;
        }
        for (String code : Locale.getISOLanguages()) {
            Locale l = new Locale(code);
            if (tag.equalsIgnoreCase(l.getDisplayLanguage(Locale.ENGLISH)) || tag.equalsIgnoreCase(l.getDisplayLanguage()))
                return l.getDisplayLanguage();
        }
        return null;
    }

    // "S01E02 - Diversity Day" or "02. Diversity Day" reads "Diversity Day"; a bare "02" stays.
    private static String cleanEpisode(String base) {
        String s = base.replaceAll("[._]", " ").trim();
        String cut = s.replaceFirst("(?i)^(s\\d+\\s*e\\d+|\\d+x\\d+|e?p?\\d{1,3}(?=[\\s\\-–:.)]))\\s*[-–:.)]?\\s*", "");
        return cut.isEmpty() ? s : cut;
    }

    private static List<Node> list(Node dir) {
        if (dir == null) return new ArrayList<>();
        List<Node> out = dir.list();
        Collections.sort(out, (a, b) -> natural(a.name(), b.name()));
        return out;
    }

    private static Node sibling(Node file, String base, List<String> exts) {
        Node dir = file.parent();
        if (dir == null) return null;
        for (String e : exts) {
            for (String c : new String[]{e, e.toUpperCase(Locale.ROOT)}) {
                Node f = dir.child(base + "." + c);
                if (f != null && f.isFile()) return f;
            }
        }
        return null;
    }

    static boolean isVideo(Node f) { return f.isFile() && !f.name().startsWith(".") && VIDEO.contains(ext(f)); }

    static String ext(Node f) {
        String n = f.name();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static String base(Node f) {
        String n = f.name();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? n : n.substring(0, dot);
    }

    private static String emptyToNull(String s) { return s == null || s.trim().isEmpty() ? null : s.trim(); }

    private static String join(List<String> s) { return android.text.TextUtils.join(", ", s); }

    // A text file (info.json), as UTF-8, without the byte-order mark some editors put first.
    static String read(Node f) throws Exception {
        String s = new String(f.bytes(1024 * 1024), StandardCharsets.UTF_8);
        return s.startsWith("\uFEFF") ? s.substring(1) : s;
    }

    // "Episode 2" before "Episode 10", ignoring case.
    static int natural(String a, String b) {
        Pattern p = Pattern.compile("(\\d+)|(\\D+)");
        Matcher ma = p.matcher(a.toLowerCase(Locale.ROOT)), mb = p.matcher(b.toLowerCase(Locale.ROOT));
        while (ma.find() && mb.find()) {
            String x = ma.group(), y = mb.group();
            int c;
            if (ma.group(1) != null && mb.group(1) != null) {
                c = Long.compare(Long.parseLong(x.length() > 18 ? x.substring(0, 18) : x), Long.parseLong(y.length() > 18 ? y.substring(0, 18) : y));
            } else c = x.compareTo(y);
            if (c != 0) return c;
        }
        return a.length() - b.length();
    }

    // For Telebisyon's web page: the drives, and each title with what it has and what looks wrong.
    JSONObject toJson(Context c) throws Exception {
        JSONObject j = new JSONObject().put("canRead", canRead(c));
        JSONArray d = new JSONArray();
        for (Drive drive : drives) d.put(new JSONObject().put("name", drive.name).put("path", drive.root.key()).put("share", drive.share));
        j.put("drives", d);
        j.put("movies", titles(movies));
        j.put("series", titles(series));
        return j;
    }

    private static JSONArray titles(List<Title> list) throws Exception {
        JSONArray a = new JSONArray();
        for (Title t : list) {
            JSONObject o = new JSONObject().put("title", t.title).put("folder", t.folder.name())
                    .put("drive", t.drive.name).put("meta", t.meta()).put("description", t.description == null ? "" : t.description)
                    .put("problems", new JSONArray(t.problems));
            if (t.poster != null) o.put("poster", t.poster.key());
            if (t.background != null) o.put("background", t.background.key());
            if (t.movie != null) o.put("video", t.movie.file.name()).put("subtitles", t.movie.subtitles.size());
            if (t.series) o.put("seasons", t.seasons.size()).put("episodes", t.episodes().size());
            a.put(o);
        }
        return a;
    }

    // The poster or background the library found with this key: the only files the web page may ask for.
    Node art(String key) {
        for (List<Title> list : Arrays.asList(movies, series))
            for (Title t : list) {
                if (t.poster != null && t.poster.key().equals(key)) return t.poster;
                if (t.background != null && t.background.key().equals(key)) return t.background;
            }
        return null;
    }
}
