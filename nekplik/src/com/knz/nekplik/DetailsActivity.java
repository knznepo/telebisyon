package com.knz.nekplik;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

// One movie or series: its background, title, details and description, and what to play. A movie has
// Play, or Resume and Start over. A series picks up where it was (the episode started, or the next one),
// and has its seasons and episodes under that.
public class DetailsActivity extends Activity {
    private Library.Title t;
    private LinearLayout episodes, seasons, buttons;
    private int season = -1;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        t = MainActivity.find(getIntent().getStringExtra("folder"));
        if (t == null) { // the app was restarted here: the library comes back first
            new Thread(() -> {
                Library.scan(this);
                runOnUiThread(() -> {
                    t = MainActivity.find(getIntent().getStringExtra("folder"));
                    if (t == null) finish();
                    else build();
                });
            }).start();
            return;
        }
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (t != null && buttons != null) { // back from the player: show what was watched
            fillButtons();
            if (t.series) showSeason(season);
            if (getCurrentFocus() == null && buttons.getChildCount() > 0) buttons.getChildAt(0).requestFocus();
        }
    }

    private void build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        ImageView back = Ui.backdrop(this, root);
        Node art = t.background != null ? t.background : t.poster;
        if (art != null) Images.loadFirst(art, getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels, b -> {
            if (b == null || isFinishing()) return;
            back.setAlpha(0f);
            back.setImageBitmap(b);
            back.animate().alpha(1f).setDuration(600).start();
            Ui.drift(back, null); // slowly zooming out, as on the main screen
        });

        ImageView brand = Ui.brand(this);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, Ui.px(this, 54));
        bp.leftMargin = Ui.px(this, 96);
        bp.topMargin = Ui.px(this, 56);
        root.addView(brand, bp);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        TextView name = Ui.title(this, 72);
        name.setText(t.title);
        name.setMaxLines(1); // one line, to leave room for the row under it
        name.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(name);
        // Score in green, the rating and HD in boxes, as on the featured top and the web version.
        LinearLayout facts = new LinearLayout(this);
        facts.setGravity(Gravity.CENTER_VERTICAL);
        facts.setPadding(0, Ui.px(this, 20), 0, 0);
        Ui.facts(this, facts, t, null);
        info.addView(facts);
        // The tagline: a quiet aside in soft white italics, as a film poster has it.
        if (t.tagline != null) {
            TextView tag = Ui.text(this, 28, 0xD9FFFFFF, false);
            tag.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.ITALIC));
            tag.setText(t.tagline);
            tag.setMaxLines(1);
            tag.setEllipsize(TextUtils.TruncateAt.END);
            tag.setShadowLayer(Ui.px(this, 8), 0, Ui.px(this, 1), 0x99000000);
            tag.setPadding(0, Ui.px(this, 20), 0, 0);
            info.addView(tag);
        }
        if (t.description != null) line(info, t.description, 28, Ui.DIM, 18, 3);
        if (!t.cast.isEmpty()) people(info, "Starring  ", TextUtils.join(", ", t.cast.subList(0, Math.min(5, t.cast.size()))), 22);
        if (t.director != null) people(info, "Director  ", t.director, 8);

        buttons = new LinearLayout(this);
        buttons.setPadding(0, Ui.px(this, 36), 0, 0);
        buttons.setClipChildren(false);
        info.addView(buttons);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(Ui.px(this, 1240), -2);
        ip.leftMargin = Ui.px(this, 96);
        ip.topMargin = Ui.px(this, 130);
        root.addView(info, ip);

        if (t.series && !t.seasons.isEmpty()) {
            LinearLayout bottom = new LinearLayout(this);
            bottom.setOrientation(LinearLayout.VERTICAL);
            bottom.setClipChildren(false);
            seasons = new LinearLayout(this);
            seasons.setPadding(Ui.px(this, 96), 0, Ui.px(this, 96), Ui.px(this, 10));
            HorizontalScrollView sh = new HorizontalScrollView(this);
            sh.setHorizontalScrollBarEnabled(false);
            sh.setClipToPadding(false);
            sh.addView(seasons);
            if (t.seasons.size() > 1) bottom.addView(sh);
            for (int i = 0; i < t.seasons.size(); i++) {
                int n = i;
                TextView b = Ui.button(this, t.seasons.get(i).name);
                b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.px(this, 22));
                b.setPadding(Ui.px(this, 28), Ui.px(this, 12), Ui.px(this, 28), Ui.px(this, 12));
                // Going along the seasons shows each one's episodes.
                View.OnFocusChangeListener look = b.getOnFocusChangeListener();
                b.setOnFocusChangeListener((v, has) -> { look.onFocusChange(v, has); if (has) showSeason(n); });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.rightMargin = Ui.px(this, 16);
                seasons.addView(b, lp);
            }
            HorizontalScrollView eh = new HorizontalScrollView(this);
            eh.setHorizontalScrollBarEnabled(false);
            eh.setClipChildren(false);
            eh.setClipToPadding(false);
            eh.setPadding(Ui.px(this, 96), Ui.px(this, 20), Ui.px(this, 96), Ui.px(this, 30));
            episodes = new LinearLayout(this);
            episodes.setClipChildren(false);
            eh.addView(episodes);
            bottom.addView(eh);
            root.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        } else if (!t.series && Library.current != null) {
            // Under a film: the rest of its collection ("More in Fast & Furious"), else "More like this".
            java.util.List<Library.Title> more = Library.current.related(t);
            boolean inCollection = !more.isEmpty() && t.collection != null && t.collection.equals(more.get(0).collection);
            if (!more.isEmpty()) root.addView(relatedRow(inCollection ? "More in " + t.collection : "More like this", more),
                    new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        }
        setContentView(root);
        fillButtons();
        if (t.series && !t.seasons.isEmpty()) {
            Library.Video next = next();
            showSeason(next == null ? 0 : next.season - 1);
        }
        if (buttons.getChildCount() > 0) buttons.getChildAt(0).requestFocus();
    }

    // A row of posters under the details; each opens that title's details.
    private View relatedRow(String label, java.util.List<Library.Title> titles) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        TextView name = Ui.text(this, 28, Color.WHITE, true);
        name.setText(label);
        name.setPadding(Ui.px(this, 96), 0, 0, Ui.px(this, 6));
        box.addView(name);
        HorizontalScrollView h = new HorizontalScrollView(this);
        h.setHorizontalScrollBarEnabled(false);
        h.setClipChildren(false);
        h.setClipToPadding(false);
        h.setPadding(Ui.px(this, 96), Ui.px(this, 18), Ui.px(this, 96), Ui.px(this, 30));
        LinearLayout strip = new LinearLayout(this);
        strip.setClipChildren(false);
        int pw = Ui.px(this, 186), ph = Ui.px(this, 279); // about eight and a half across
        for (Library.Title o : titles) {
            View card = Ui.poster(this, o, pw, ph);
            card.setOnClickListener(v -> startActivity(new android.content.Intent(this, DetailsActivity.class).putExtra("folder", o.folder.key())));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(pw, ph);
            lp.rightMargin = Ui.px(this, 24);
            strip.addView(card, lp);
        }
        h.addView(strip);
        box.addView(h);
        return box;
    }

    // "Starring" faint, the names brighter, as the web version's side column.
    private void people(LinearLayout into, String label, String names, int top) {
        TextView v = Ui.text(this, 24, Ui.DIM, false);
        android.text.SpannableString s = new android.text.SpannableString(label + names);
        s.setSpan(new android.text.style.ForegroundColorSpan(Ui.FAINT), 0, label.length(), 0);
        v.setText(s);
        v.setMaxLines(1);
        v.setEllipsize(TextUtils.TruncateAt.END);
        v.setPadding(0, Ui.px(this, top), 0, 0);
        into.addView(v);
    }

    private void line(LinearLayout into, String text, float size, int color, int top, int lines) {
        if (text == null || text.isEmpty()) return;
        TextView v = Ui.text(this, size, color, false);
        v.setText(text);
        v.setMaxLines(lines);
        v.setEllipsize(TextUtils.TruncateAt.END);
        v.setLineSpacing(Ui.px(this, 6), 1f);
        v.setShadowLayer(Ui.px(this, 8), 0, Ui.px(this, 1), 0x99000000);
        v.setPadding(0, Ui.px(this, top), 0, 0);
        into.addView(v);
    }

    // Play, Resume, Start over: from what was watched.
    private void fillButtons() {
        View had = buttons.findFocus();
        buttons.removeAllViews();
        if (t.series) {
            Library.Video v = next();
            if (v == null) return;
            Progress.Entry p = Progress.get(this, v.file);
            boolean resume = p != null && p.resumable();
            add((resume ? "Resume " : "Play ") + "S" + v.season + ":E" + v.number, Ui.Icon.PLAY, () -> play(v, resume));
        } else if (t.movie != null) {
            Progress.Entry p = Progress.get(this, t.movie.file);
            if (p != null && p.resumable()) {
                add("Resume  " + Ui.time(p.position), Ui.Icon.PLAY, () -> play(t.movie, true));
                add("Start over", Ui.Icon.RESTART, () -> play(t.movie, false));
            } else add("Play", Ui.Icon.PLAY, () -> play(t.movie, false));
        }
        if (had != null && buttons.getChildCount() > 0) buttons.getChildAt(0).requestFocus();
    }

    private void add(String label, int icon, Runnable r) {
        TextView b = Ui.bigButton(this, label, icon, null);
        b.setOnClickListener(v -> r.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = Ui.px(this, 20);
        buttons.addView(b, lp);
    }

    // Where the series is at: the episode watched last if it isn't finished, else the one after it.
    // Never started: the first.
    private Library.Video next() {
        List<Library.Video> all = t.episodes();
        if (all.isEmpty()) return null;
        Library.Video latest = null;
        long when = -1;
        for (Library.Video v : all) {
            Progress.Entry p = Progress.get(this, v.file);
            if (p != null && p.when > when) { when = p.when; latest = v; }
        }
        if (latest == null) return all.get(0);
        Progress.Entry p = Progress.get(this, latest.file);
        int i = all.indexOf(latest);
        return p.watched() && i + 1 < all.size() ? all.get(i + 1) : latest;
    }

    private void showSeason(int n) {
        if (episodes == null || n < 0 || n >= t.seasons.size()) return;
        season = n;
        episodes.removeAllViews();
        for (Library.Video v : t.seasons.get(n).episodes) episodes.addView(episode(v));
    }

    // An episode: its picture (or the series' background), number and name, a line about it, and how
    // much of it was watched.
    private View episode(Library.Video v) {
        int w = Ui.px(this, 360), h = Ui.px(this, 203), r = Ui.px(this, 12);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setFocusable(true);
        box.setClickable(true);
        box.setClipChildren(false);

        FrameLayout pic = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(r);
        bg.setColor(0xFF2A2326);
        pic.setBackground(bg);
        pic.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) { o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r); }
        });
        pic.setClipToOutline(true);
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        pic.addView(img, new FrameLayout.LayoutParams(-1, -1));
        Node art = v.thumb != null ? v.thumb : t.background != null ? t.background : t.poster;
        Images.into(img, art, w, h);
        Progress.Entry p = Progress.get(this, v.file);
        if (p != null) {
            View track = new View(this);
            track.setBackgroundColor(0x66FFFFFF);
            pic.addView(track, new FrameLayout.LayoutParams(-1, Ui.px(this, 7), Gravity.BOTTOM));
            View done = new View(this);
            done.setBackgroundColor(Ui.RED);
            pic.addView(done, new FrameLayout.LayoutParams(Math.max(1, Math.round(w * p.fraction())), Ui.px(this, 7), Gravity.BOTTOM));
        }
        box.addView(pic, new LinearLayout.LayoutParams(w, h));

        TextView name = Ui.text(this, 24, Color.WHITE, true);
        name.setText(v.number + ".  " + v.title);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(0, Ui.px(this, 14), 0, 0);
        box.addView(name, new LinearLayout.LayoutParams(w, -2));
        if (v.description != null) {
            TextView d = Ui.text(this, 20, Ui.FAINT, false);
            d.setText(v.description);
            d.setMaxLines(2);
            d.setEllipsize(TextUtils.TruncateAt.END);
            d.setPadding(0, Ui.px(this, 6), 0, 0);
            box.addView(d, new LinearLayout.LayoutParams(w, -2));
        }

        GradientDrawable edge = new GradientDrawable();
        edge.setCornerRadius(r);
        edge.setStroke(Ui.px(this, 5), Color.WHITE);
        box.setOnFocusChangeListener((view, has) -> {
            pic.setForeground(has ? edge : null);
            box.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(150).start();
        });
        box.setOnClickListener(view -> play(v, p != null && p.resumable()));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = Ui.px(this, 32);
        box.setLayoutParams(lp);
        return box;
    }

    private void play(Library.Video v, boolean resume) {
        startActivity(PlayerActivity.intent(this, t, v, resume));
    }
}
