package com.knz.nekplik;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

// Where each video was stopped, by its key (its path on the drive, or share://<computer>/<path>): for
// Resume, the progress bars and the Continue watching row. Near the end counts as watched.
final class Progress {
    private static final String PREFS = "progress";

    static final class Entry {
        String path;
        long position, duration, when;

        float fraction() { return duration > 0 ? Math.min(1f, (float) position / duration) : 0; }

        boolean watched() { return duration > 0 && fraction() >= 0.95f; }

        // Worth resuming: past the first minute and not finished.
        boolean resumable() { return position > 60000 && !watched(); }
    }

    static Entry get(Context c, Node f) {
        String v = c.getSharedPreferences(PREFS, 0).getString(f.key(), null);
        return v == null ? null : parse(f.key(), v);
    }

    static void save(Context c, Node f, long position, long duration) {
        if (duration <= 0) return;
        c.getSharedPreferences(PREFS, 0).edit()
                .putString(f.key(), position + "," + duration + "," + System.currentTimeMillis()).apply();
    }

    // Everything started and not finished, latest first.
    static List<Entry> started(Context c) {
        List<Entry> out = new ArrayList<>();
        SharedPreferences p = c.getSharedPreferences(PREFS, 0);
        for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
            Entry x = parse(e.getKey(), String.valueOf(e.getValue()));
            if (x != null && x.resumable()) out.add(x);
        }
        Collections.sort(out, (a, b) -> Long.compare(b.when, a.when));
        return out;
    }

    private static Entry parse(String path, String v) {
        String[] p = v.split(",");
        if (p.length < 3) return null;
        try {
            Entry e = new Entry();
            e.path = path;
            e.position = Long.parseLong(p[0]);
            e.duration = Long.parseLong(p[1]);
            e.when = Long.parseLong(p[2]);
            return e;
        } catch (NumberFormatException x) {
            return null;
        }
    }
}
