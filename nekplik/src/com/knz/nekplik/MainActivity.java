package com.knz.nekplik;

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
import java.util.Random;
import java.util.TreeMap;

// The browsing screen: a title big at the top (its background, title, year, rating and description),
// and rows of posters under it: Continue watching, Recently added, Movies, Series, a row per collection
// (Fast & Furious), and a row per genre, with Search at the top right. At the top, a few featured
// titles take turns, with Play and More info, until you go down to the rows; then it shows the focused
// one. With no drive it says so; plugging one in or taking it out rescans by itself.
public class MainActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private ImageView back, under; // two backgrounds, to fade one into the other
    private boolean topShowing;
    private android.animation.Animator drift, driftUnder;
    // The featured titles at the top, changing every few seconds until you go down to the rows.
    private static final long FEATURE_MS = 9000;
    private final List<Library.Title> picks = new ArrayList<>();
    private int pickAt;
    private boolean featuring;
    private LinearLayout heroButtons, dots;
    private TextView playButton, infoButton;
    private final Runnable rotate = new Runnable() {
        @Override
        public void run() {
            if (!featuring || picks.size() < 2) return;
            showPick((pickAt + 1) % picks.size());
        }
    };
    private TextView title, description, message, search;
    private final long shuffleSeed = new Random().nextLong(); // the genre rows' order, this visit
    private LinearLayout meta; // score, year, rating, length, HD, genres: as on the web version
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
    private Intro intro;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        back = Ui.backdrop(this, root);
        under = new ImageView(this);
        under.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(under, 0, new FrameLayout.LayoutParams(-1, -1));
        back.setAlpha(0f);

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
        title.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));
        title.setLetterSpacing(-0.02f);
        title.setShadowLayer(Ui.px(this, 24), 0, Ui.px(this, 2), 0x73000000);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        meta = new LinearLayout(this);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        meta.setPadding(0, Ui.px(this, 20), 0, 0);
        description = Ui.text(this, 28, Ui.DIM, false);
        description.setShadowLayer(Ui.px(this, 8), 0, Ui.px(this, 1), 0x99000000);
        description.setMaxLines(3);
        description.setEllipsize(TextUtils.TruncateAt.END);
        description.setLineSpacing(Ui.px(this, 6), 1f);
        description.setPadding(0, Ui.px(this, 20), 0, 0);
        hero.addView(title);
        hero.addView(meta);
        hero.addView(description);
        // Play and More info, for the featured title. Down goes to the rows.
        heroButtons = new LinearLayout(this);
        heroButtons.setPadding(0, Ui.px(this, 34), 0, 0);
        playButton = Ui.bigButton(this, "Play", Ui.Icon.PLAY, this::featureAgain);
        playButton.setId(View.generateViewId());
        infoButton = Ui.bigButton(this, "More info", Ui.Icon.INFO, this::featureAgain);
        search.setId(View.generateViewId()); // right of More info, or up from either: Search
        infoButton.setNextFocusRightId(search.getId());
        infoButton.setNextFocusUpId(search.getId());
        playButton.setNextFocusUpId(search.getId());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, Ui.px(this, 70));
        blp.rightMargin = Ui.px(this, 18);
        heroButtons.addView(playButton, blp);
        heroButtons.addView(infoButton, new LinearLayout.LayoutParams(-2, Ui.px(this, 70)));
        heroButtons.setClipChildren(false);
        hero.setClipChildren(false);
        hero.addView(heroButtons);
        playButton.setOnClickListener(v -> { if (!picks.isEmpty()) playFeatured(picks.get(pickAt)); });
        infoButton.setOnClickListener(v -> { if (!picks.isEmpty()) details(picks.get(pickAt)); });
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(Ui.px(this, 1000), -2);
        hp.leftMargin = Ui.px(this, 96);
        hp.topMargin = Ui.px(this, 150);
        root.addView(hero, hp);

        dots = new LinearLayout(this);
        FrameLayout.LayoutParams dp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        dp.rightMargin = Ui.px(this, 96);
        dp.topMargin = Ui.px(this, 520);
        root.addView(dots, dp);

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
        if (saved == null) intro = Intro.show(this, root); // opening NEKPLIK: the logo and its sound, once
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
        if (featuring && picks.size() > 1) { main.removeCallbacks(rotate); main.postDelayed(rotate, FEATURE_MS); }
    }

    // While the intro shows, a key skips it (Back too, to the screen rather than out of the app).
    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        if (intro != null && intro.showing()) {
            if (e.getAction() == android.view.KeyEvent.ACTION_UP) intro.finish();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    protected void onPause() {
        if (intro != null) intro.stop();
        super.onPause();
        unregisterReceiver(drives);
        main.removeCallbacks(rescan);
        main.removeCallbacks(again);
        main.removeCallbacks(rotate);
    }

    private void scan() {
        if (!Library.canRead(this)) {
            say("NEKPLIK can't read drives yet.\nOn a computer, run:  sh nekplik.sh <TV_IP>");
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
        under.setImageDrawable(null);
        backdropNow = null;
        shown = "";
        featuring = false;
        main.removeCallbacks(rotate);
        dots.removeAllViews();
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
                say("Connect a USB drive or hard disk, or share your movies from a computer (share.py).\nNEKPLIK shows the Movies and Series folders on it.");
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
        // Genres: a different few each time the app opens, in a random order, their titles shuffled too.
        // The same seed all visit long, so the rows stay put while browsing.
        Map<String, List<Library.Title>> genres = new TreeMap<>();
        for (Library.Title t : all) for (String g : t.genres) {
            List<Library.Title> l = genres.get(g);
            if (l == null) genres.put(g, l = new ArrayList<>());
            l.add(t);
        }
        List<String> g = new ArrayList<>();
        for (Map.Entry<String, List<Library.Title>> e : genres.entrySet()) if (e.getValue().size() >= 3) g.add(e.getKey());
        Collections.shuffle(g, new Random(shuffleSeed));
        for (String name : g.subList(0, Math.min(10, g.size()))) {
            if (lists.containsKey(name)) continue;
            List<Library.Title> ts = genres.get(name);
            Collections.sort(ts, (a, b) -> a.folder.name().compareTo(b.folder.name()));
            Collections.shuffle(ts, new Random(shuffleSeed ^ name.hashCode()));
            lists.put(name, cards(ts));
        }

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
                View v = card(c, row, WIDE_ROWS.contains(e.getKey()));
                if (rows.getChildCount() == 0) v.setNextFocusUpId(playButton.getId()); // up from the first row: Play
                strip.addView(v);
                if (first == null) first = v;
                if (focus == null && c.key.equals(focusedKey)) focus = v;
            }
            rows.addView(row);
        }
        // The featured titles: what's being watched first, then a few picked at random, with a background
        // and a description. Coming back from a title, the focus goes back to it instead.
        picks.clear();
        List<Card> going = lists.get("Continue watching");
        if (going != null && !going.isEmpty()) picks.add(going.get(0).title);
        List<Library.Title> pool = new ArrayList<>();
        for (Library.Title t : all) if (t.background != null && t.description != null && !picks.contains(t)) pool.add(t);
        Collections.shuffle(pool);
        for (Library.Title t : pool) { if (picks.size() >= 6) break; picks.add(t); }
        if (picks.isEmpty()) picks.add(all.get(0));
        dots.removeAllViews();
        if (picks.size() > 1) for (int i = 0; i < picks.size(); i++) {
            FrameLayout d = new FrameLayout(this);
            d.setBackground(Ui.round(0x4DFFFFFF, Ui.px(this, 3)));
            d.setClipToOutline(true);
            View fill = new View(this);
            fill.setBackground(Ui.round(Color.WHITE, Ui.px(this, 3)));
            fill.setPivotX(0);
            fill.setScaleX(0f);
            d.addView(fill, new FrameLayout.LayoutParams(-1, -1));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.px(this, 38), Ui.px(this, 5));
            lp.leftMargin = Ui.px(this, 10);
            dots.addView(d, lp);
        }
        pickAt = 0;
        if (focus != null) {
            featuring = false;
            heroButtons.setAlpha(0f);
            dots.setAlpha(0f);
            focus.requestFocus();
        } else {
            featuring = false; // so featureAgain shows the first one
            playButton.requestFocus();
        }
    }

    // The focus is on Play or More info: the featured titles again, changing by themselves.
    private void featureAgain() {
        scroller.smoothScrollTo(0, 0);
        if (featuring) return;
        featuring = true;
        focusedKey = null;
        title.setMaxLines(1);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.px(this, 86));
        description.setMaxLines(2);
        scroller.animate().translationY(Ui.px(this, 160)).setDuration(450)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(2f)).start();
        heroButtons.animate().alpha(1f).setDuration(250).start();
        dots.animate().alpha(1f).setDuration(250).start();
        showPick(pickAt);
    }

    private void showPick(int i) {
        if (picks.isEmpty()) return;
        pickAt = i;
        Library.Title t = picks.get(i);
        // The text slides up into place as the picture fades.
        hero.animate().cancel();
        hero.animate().alpha(0f).translationY(-Ui.px(this, 10)).setDuration(220)
                .setInterpolator(new android.view.animation.AccelerateInterpolator()).withEndAction(() -> {
                    title.setText(t.title);
                    Ui.facts(this, meta, t, null);
                    description.setText(t.description == null ? "" : t.description);
                    Progress.Entry p = t.movie != null ? Progress.get(this, t.movie.file) : null;
                    playButton.setText(p != null && p.resumable() ? "Resume" : "Play");
                    hero.setTranslationY(Ui.px(this, 18));
                    hero.animate().alpha(1f).translationY(0).setDuration(520)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator(2f)).withEndAction(null).start();
                }).start();
        backdrop(t.background != null ? t.background : t.poster, 0);
        // The bar of the one showing fills up until the next one.
        for (int k = 0; k < dots.getChildCount(); k++) {
            View fill = ((FrameLayout) dots.getChildAt(k)).getChildAt(0);
            fill.animate().cancel();
            fill.setScaleX(k < i ? 1f : 0f);
            if (k == i) fill.animate().scaleX(1f).setDuration(FEATURE_MS)
                    .setInterpolator(new android.view.animation.LinearInterpolator()).start();
        }
        main.removeCallbacks(rotate);
        if (picks.size() > 1) main.postDelayed(rotate, FEATURE_MS);
    }

    // Going down to the rows: the top of the screen follows the focus instead.
    private void browse() {
        if (!featuring) return;
        featuring = false;
        main.removeCallbacks(rotate);
        title.setMaxLines(2);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.px(this, 72));
        description.setMaxLines(3);
        scroller.animate().translationY(0).setDuration(380)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(2f)).start();
        heroButtons.animate().alpha(0f).setDuration(200).start();
        dots.animate().alpha(0f).setDuration(200).start();
    }

    private void playFeatured(Library.Title t) {
        if (t.movie != null) startActivity(PlayerActivity.intent(this, t, t.movie, true));
        else details(t);
    }

    private void details(Library.Title t) {
        startActivity(new Intent(this, DetailsActivity.class).putExtra("folder", t.folder.key()));
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
    // Wide cards (the background, with the title on it) for these rows; posters for the rest.
    private static final List<String> WIDE_ROWS = java.util.Arrays.asList("Continue watching", "Recently added");

    private View card(Card c, LinearLayout row, boolean wide) {
        int w = Ui.px(this, wide ? 400 : 200), h = Ui.px(this, wide ? 225 : 300), r = Ui.px(this, 14);
        Node pic = wide ? (c.title.background != null ? c.title.background : c.title.poster) : c.title.poster;
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
        Images.into(poster, pic, w, h);
        if (wide && pic != null) { // the background has no title on it: the name along the bottom
            View shade = new View(this);
            shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xD9000000, 0x00000000}));
            card.addView(shade, new FrameLayout.LayoutParams(-1, h / 2, Gravity.BOTTOM));
            TextView label = Ui.text(this, 24, Color.WHITE, true);
            label.setText(c.title.title);
            label.setMaxLines(1);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setShadowLayer(Ui.px(this, 6), 0, 0, 0xB3000000);
            FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
            llp.leftMargin = llp.rightMargin = Ui.px(this, 16);
            llp.bottomMargin = Ui.px(this, c.progress != null ? 22 : 14);
            card.addView(label, llp);
        }

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
            card.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).setDuration(has ? 220 : 160)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(2f)).start();
            card.setForeground(has ? edge : null);
            card.setElevation(has ? Ui.px(this, 20) : 0);
            if (!has) return;
            browse();
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
        Ui.facts(this, meta, t, c.video != null && t.series ? "S" + c.video.season + ":E" + c.video.number + "  " + c.video.title : null);
        description.setText(t.description == null ? "" : t.description);
        hero.animate().cancel();
        hero.setTranslationY(0);
        hero.setAlpha(0.35f);
        hero.animate().alpha(1f).setDuration(260).start();
        // Only once the focus rests, so going along a row quickly doesn't load every background.
        backdrop(t.background != null ? t.background : t.poster, 250);
    }

    // The picture behind the top of the screen: the new one fades in over the old, then slowly zooms out.
    private void backdrop(Node art, long delay) {
        if (art == null ? backdropNow == null : art.equals(backdropNow)) return;
        backdropNow = art;
        main.removeCallbacksAndMessages(back);
        main.postAtTime(() -> {
            if (art == null) {
                back.animate().alpha(0f).setDuration(300).start();
                under.animate().alpha(0f).setDuration(300).start();
                return;
            }
            int sw = getResources().getDisplayMetrics().widthPixels, sh = getResources().getDisplayMetrics().heightPixels;
            Images.loadFirst(art, sw, sh, b -> {
                if (!art.equals(backdropNow) || b == null) return;
                if (topShowing) { // the new one goes underneath, and the top one fades away
                    under.animate().cancel();
                    under.setImageBitmap(b);
                    under.setAlpha(1f);
                    driftUnder = Ui.drift(under, driftUnder);
                    back.animate().alpha(0f).setDuration(700).start();
                } else { // the new one fades in on top
                    under.setAlpha(1f);
                    back.setImageBitmap(b);
                    back.setAlpha(0f);
                    drift = Ui.drift(back, drift);
                    back.animate().alpha(1f).setDuration(700).start();
                }
                topShowing = !topShowing;
            });
        }, back, android.os.SystemClock.uptimeMillis() + delay);
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
