package com.knz.nekplix;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// The browsing screen: the highlighted title big at the top (its background, title, year, rating and
// description), and rows of posters under it: Continue watching, Recently added, Movies, Series, a row
// per collection (Fast & Furious), and a row per genre, with Search at the top right. With no drive it says so; plugging one in or taking it out rescans by itself.
public class MainActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private ImageView back;
    private TextView title, meta, description, message, search;
    private LinearLayout hero, rows;
    private ScrollView scroller;
    private String shown = ""; // what the rows were built from, so a rescan with no change keeps the focus
    private String focusedKey;
    private Node backdropNow;

    // A drive was plugged in or taken out: look again, once it's settled.
    private final BroadcastReceiver drives = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            main.removeCallbacks(rescan);
            main.postDelayed(rescan, 1500);
        }
    };
    private final Runnable rescan = this::scan;
    // Computers can start or stop sharing at any time: look again every 30 seconds while this is open.
    private final Runnable again = new Runnable() {
        @Override
        public void run() {
            scan();
            main.postDelayed(this, 30000);
        }
    };
    private volatile boolean scanning;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        back = Ui.backdrop(this, root);

        ImageView brand = Ui.brand(this);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, Ui.px(this, 54));
        bp.leftMargin = Ui.px(this, 96);
        bp.topMargin = Ui.px(this, 56);
        root.addView(brand, bp);

        // Search, at the top right: up from the first row, or the remote's search key.
        search = Ui.button(this, "Search");
        search.setOnClickListener(v -> openSearch());
        FrameLayout.LayoutParams qp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        qp.rightMargin = Ui.px(this, 96);
        qp.topMargin = Ui.px(this, 52);
        root.addView(search, qp);

        hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        title = Ui.text(this, 72, Color.WHITE, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        meta = Ui.text(this, 26, Ui.DIM, false);
        meta.setPadding(0, Ui.px(this, 18), 0, 0);
        description = Ui.text(this, 28, Ui.DIM, false);
        description.setMaxLines(3);
        description.setEllipsize(TextUtils.TruncateAt.END);
        description.setLineSpacing(Ui.px(this, 6), 1f);
        description.setPadding(0, Ui.px(this, 20), 0, 0);
        hero.addView(title);
        hero.addView(meta);
        hero.addView(description);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(Ui.px(this, 1000), -2);
        hp.leftMargin = Ui.px(this, 96);
        hp.topMargin = Ui.px(this, 150);
        root.addView(hero, hp);

        scroller = new ScrollView(this);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setClipChildren(false);
        scroller.setClipToPadding(false);
        scroller.setPadding(0, 0, 0, Ui.px(this, 300));
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipChildren(false);
        scroller.addView(rows, new FrameLayout.LayoutParams(-1, -2));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-1, -1);
        sp.topMargin = Ui.px(this, 540);
        root.addView(scroller, sp);

        message = Ui.text(this, 34, Ui.DIM, false);
        message.setGravity(Gravity.CENTER);
        message.setLineSpacing(Ui.px(this, 10), 1f);
        root.addView(message, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        say("Looking for movies…");
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_MEDIA_MOUNTED);
        f.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        f.addAction(Intent.ACTION_MEDIA_REMOVED);
        f.addAction(Intent.ACTION_MEDIA_BAD_REMOVAL);
        f.addAction(Intent.ACTION_MEDIA_EJECT);
        f.addDataScheme("file");
        registerReceiver(drives, f);
        scan(); // also picks up what was watched since
        main.postDelayed(again, 30000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(drives);
        main.removeCallbacks(rescan);
        main.removeCallbacks(again);
    }

    private void scan() {
        if (!Library.canRead(this)) {
            say("NEKPLIX can't read drives yet.\nOn a computer, run:  sh nekplix.sh <TV_IP>");
            return;
        }
        if (scanning) return; // one at a time: asking the Wi-Fi takes a second or two
        scanning = true;
        new Thread(() -> {
            Library lib = Library.scan(this);
            scanning = false;
            main.post(() -> show(lib));
        }, "scan").start();
    }

    private void openSearch() { startActivity(new Intent(this, SearchActivity.class)); }

    @Override
    public boolean onKeyDown(int code, android.view.KeyEvent e) {
        if (code == android.view.KeyEvent.KEYCODE_SEARCH) { openSearch(); return true; }
        return super.onKeyDown(code, e);
    }

    private void say(String text) {
        message.setText(text);
        message.setVisibility(View.VISIBLE);
        hero.setVisibility(View.INVISIBLE);
        scroller.setVisibility(View.INVISIBLE);
        back.setImageDrawable(null);
        backdropNow = null;
        shown = "";
    }

    // One card on a row: a title, or for Continue watching, the episode or movie to go on with.
    private static final class Card {
        Library.Title title;
        Library.Video video; // set for Continue watching: plays straight away
        Progress.Entry progress;
        String key, badge;
    }

    private void show(Library lib) {
        if (lib.empty()) {
            if (lib.drives.isEmpty() && Library.drives(this).isEmpty())
                say("Connect a USB drive or hard disk, or share your movies from a computer (share.py).\nNEKPLIX shows the Movies and Series folders on it.");
            else say("No movies or series found.\nPut them in a Movies or Series folder on the drive, one folder each.");
            return;
        }
        Map<String, List<Card>> lists = new LinkedHashMap<>();
        lists.put("Continue watching", continueWatching(lib));
        List<Library.Title> all = new ArrayList<>(lib.movies);
        all.addAll(lib.series);
        List<Library.Title> recent = new ArrayList<>(all);
        Collections.sort(recent, (a, b) -> Long.compare(b.folder.lastModified(), a.folder.lastModified()));
        if (all.size() > 6) lists.put("Recently added", cards(recent.subList(0, Math.min(15, recent.size()))));
        lists.put("Movies", cards(lib.movies));
        lists.put("Series", cards(lib.series));
        // A row per collection ("Fast & Furious"), its films in release order.
        for (Map.Entry<String, List<Library.Title>> c : lib.collections().entrySet())
            if (!lists.containsKey(c.getKey())) lists.put(c.getKey(), cards(c.getValue()));
        // A row per genre with two or more titles, the biggest first.
        Map<String, List<Library.Title>> genres = new HashMap<>();
        for (Library.Title t : all) for (String g : t.genres) {
            List<Library.Title> l = genres.get(g);
            if (l == null) genres.put(g, l = new ArrayList<>());
            l.add(t);
        }
        List<Map.Entry<String, List<Library.Title>>> g = new ArrayList<>(genres.entrySet());
        Collections.sort(g, (a, b) -> b.getValue().size() - a.getValue().size());
        for (Map.Entry<String, List<Library.Title>> e : g.subList(0, Math.min(8, g.size())))
            if (e.getValue().size() >= 2) lists.put(e.getKey(), cards(e.getValue()));

        // Nothing changed since last time: leave the screen (and the focus) as it is.
        StringBuilder sig = new StringBuilder();
        for (Map.Entry<String, List<Card>> e : lists.entrySet()) {
            sig.append(e.getKey()).append(':');
            for (Card c : e.getValue()) sig.append(c.key).append(c.progress == null ? "" : c.progress.position / 60000).append(',');
        }
        if (sig.toString().equals(shown)) return;
        shown = sig.toString();

        message.setVisibility(View.GONE);
        hero.setVisibility(View.VISIBLE);
        scroller.setVisibility(View.VISIBLE);
        rows.removeAllViews();
        View focus = null, first = null;
        for (Map.Entry<String, List<Card>> e : lists.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            LinearLayout row = row(e.getKey());
            LinearLayout strip = (LinearLayout) ((HorizontalScrollView) row.getChildAt(1)).getChildAt(0);
            for (Card c : e.getValue()) {
                View v = card(c, row);
                strip.addView(v);
                if (first == null) first = v;
                if (focus == null && c.key.equals(focusedKey)) focus = v;
            }
            rows.addView(row);
        }
        (focus != null ? focus : first).requestFocus();
    }

    private List<Card> cards(List<Library.Title> titles) {
        List<Card> out = new ArrayList<>();
        for (Library.Title t : titles) {
            Card c = new Card();
            c.title = t;
            c.key = t.folder.key();
            out.add(c);
        }
        return out;
    }

    // What was started and not finished, latest first: the movie, or the episode the series is at.
    private List<Card> continueWatching(Library lib) {
        Map<String, Card> byPath = new HashMap<>();
        for (Library.Title t : lib.movies) if (t.movie != null) byPath.put(t.movie.file.key(), card(t, t.movie, null));
        for (Library.Title t : lib.series) for (Library.Video v : t.episodes())
            byPath.put(v.file.key(), card(t, v, "S" + v.season + ":E" + v.number));
        List<Card> out = new ArrayList<>();
        List<Library.Title> seen = new ArrayList<>();
        for (Progress.Entry e : Progress.started(this)) {
            Card c = byPath.get(e.path);
            if (c == null || seen.contains(c.title)) continue; // on a drive that isn't in, or an older episode
            seen.add(c.title);
            c.progress = e;
            out.add(c);
        }
        return out;
    }

    private static Card card(Library.Title t, Library.Video v, String badge) {
        Card c = new Card();
        c.title = t;
        c.video = v;
        c.badge = badge;
        c.key = "continue:" + v.file.key();
        return c;
    }

    private LinearLayout row(String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setClipChildren(false);
        TextView name = Ui.text(this, 30, Color.WHITE, true);
        name.setText(label);
        name.setPadding(Ui.px(this, 96), 0, 0, Ui.px(this, 18));
        row.addView(name);
        HorizontalScrollView h = new HorizontalScrollView(this);
        h.setHorizontalScrollBarEnabled(false);
        h.setClipChildren(false);
        h.setClipToPadding(false);
        h.setPadding(Ui.px(this, 96), Ui.px(this, 22), Ui.px(this, 96), Ui.px(this, 40));
        LinearLayout strip = new LinearLayout(this);
        strip.setClipChildren(false);
        h.addView(strip);
        row.addView(h);
        return row;
    }

    // A poster card. Focused, it grows a little with a white edge, and the top of the screen shows it.
    private View card(Card c, LinearLayout row) {
        int w = Ui.px(this, 200), h = Ui.px(this, 300), r = Ui.px(this, 14);
        FrameLayout card = new FrameLayout(this);
        card.setFocusable(true);
        card.setClickable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(r);
        bg.setColor(0xFF2A2326);
        card.setBackground(bg);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); }
        });
        card.setClipToOutline(true);

        // Without a poster: the title, so it's still clear what it is.
        TextView name = Ui.text(this, 26, Color.WHITE, true);
        name.setText(c.title.title);
        name.setGravity(Gravity.CENTER);
        name.setPadding(Ui.px(this, 14), 0, Ui.px(this, 14), 0);
        card.addView(name, new FrameLayout.LayoutParams(-1, -1));
        ImageView poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(poster, new FrameLayout.LayoutParams(-1, -1));
        Images.into(poster, c.title.poster, w, h);

        if (c.badge != null) {
            TextView badge = Ui.text(this, 20, Color.WHITE, true);
            badge.setText(c.badge);
            badge.setPadding(Ui.px(this, 10), Ui.px(this, 6), Ui.px(this, 10), Ui.px(this, 6));
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(Ui.px(this, 8));
            pill.setColor(0xCC000000);
            badge.setBackground(pill);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
            lp.leftMargin = lp.topMargin = Ui.px(this, 10);
            card.addView(badge, lp);
        }
        if (c.progress != null) {
            View track = new View(this);
            track.setBackgroundColor(0x66FFFFFF);
            card.addView(track, new FrameLayout.LayoutParams(-1, Ui.px(this, 8), Gravity.BOTTOM));
            View done = new View(this);
            done.setBackgroundColor(Ui.RED);
            card.addView(done, new FrameLayout.LayoutParams(Math.max(1, Math.round(w * c.progress.fraction())), Ui.px(this, 8), Gravity.BOTTOM));
        }

        GradientDrawable edge = new GradientDrawable();
        edge.setCornerRadius(r);
        edge.setStroke(Ui.px(this, 5), Color.WHITE);
        card.setOnFocusChangeListener((v, has) -> {
            card.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).setDuration(150).start();
            card.setForeground(has ? edge : null);
            card.setElevation(has ? Ui.px(this, 20) : 0);
            if (!has) return;
            focusedKey = c.key;
            feature(c);
            scroller.smoothScrollTo(0, row.getTop());
        });
        card.setOnClickListener(v -> open(c));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.rightMargin = Ui.px(this, 28);
        card.setLayoutParams(lp);
        return card;
    }

    // The top of the screen: the focused title's background, name, details and description.
    private void feature(Card c) {
        Library.Title t = c.title;
        title.setText(t.title);
        String m = t.meta();
        if (c.video != null && t.series) m = "S" + c.video.season + ":E" + c.video.number + "  " + c.video.title + (m.isEmpty() ? "" : "  ·  " + m);
        meta.setText(m);
        meta.setVisibility(m.isEmpty() ? View.GONE : View.VISIBLE);
        description.setText(t.description == null ? "" : t.description);
        Node art = t.background != null ? t.background : t.poster;
        if (art == null ? backdropNow == null : art.equals(backdropNow)) return;
        backdropNow = art;
        main.removeCallbacksAndMessages(back);
        // Only once the focus rests, so going along a row quickly doesn't load every background.
        main.postAtTime(() -> {
            if (art == null) { back.animate().alpha(0f).setDuration(250).start(); return; }
            int sw = getResources().getDisplayMetrics().widthPixels, sh = getResources().getDisplayMetrics().heightPixels;
            Images.load(art, sw, sh, b -> {
                if (!art.equals(backdropNow) || b == null) return;
                back.setAlpha(0f);
                back.setImageBitmap(b);
                back.animate().alpha(1f).setDuration(350).start();
            });
        }, back, android.os.SystemClock.uptimeMillis() + 250);
    }

    private void open(Card c) {
        if (c.video != null) {
            startActivity(PlayerActivity.intent(this, c.title, c.video, true));
            return;
        }
        startActivity(new Intent(this, DetailsActivity.class).putExtra("folder", c.title.folder.key()));
    }

    static Library.Title find(String folder) {
        Library lib = Library.current;
        if (lib == null) return null;
        for (List<Library.Title> list : java.util.Arrays.asList(lib.movies, lib.series))
            for (Library.Title t : list) if (t.folder.key().equals(folder)) return t;
        return null;
    }
}
