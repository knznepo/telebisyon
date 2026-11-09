package com.knz.nekplik;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// Search, laid out for a remote: a keyboard on the left to type with the arrows (or a keyboard's keys,
// or the TV's voice input), words from the library to pick under it, and on the right the titles that
// match, changing with every letter. Before anything's typed, it shows what was added last.
public class SearchActivity extends Activity {
    private static final int VOICE = 1;
    private static final int COLUMNS = 5;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final StringBuilder query = new StringBuilder();
    private TextView field, heading;
    private LinearLayout suggestions, grid;
    private ScrollView results;
    private Library lib;
    private View firstKey;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Intro.opened = true; // NEKPLIK is already open: no intro when going back to the main screen
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        android.widget.ImageView brand = Ui.brand(this);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, Ui.px(this, 54));
        bp.leftMargin = Ui.px(this, 96);
        bp.topMargin = Ui.px(this, 56);
        root.addView(brand, bp);

        // Left: what's typed, the keyboard, and the suggestions.
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        field = Ui.text(this, 40, Color.WHITE, true);
        field.setSingleLine(true);
        field.setEllipsize(android.text.TextUtils.TruncateAt.START);
        field.setPadding(0, 0, 0, Ui.px(this, 16));
        left.addView(field, new LinearLayout.LayoutParams(-1, -2));
        View line = new View(this);
        line.setBackgroundColor(0x55FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.px(this, 2));
        lp.bottomMargin = Ui.px(this, 28);
        left.addView(line, lp);
        left.addView(keyboard());
        suggestions = new LinearLayout(this);
        suggestions.setOrientation(LinearLayout.VERTICAL);
        suggestions.setPadding(0, Ui.px(this, 30), 0, 0);
        left.addView(suggestions);
        FrameLayout.LayoutParams leftP = new FrameLayout.LayoutParams(Ui.px(this, 560), -1);
        leftP.leftMargin = Ui.px(this, 96);
        leftP.topMargin = Ui.px(this, 160);
        root.addView(left, leftP);

        // Right: the matching titles.
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setClipChildren(false);
        heading = Ui.text(this, 30, Color.WHITE, true);
        right.addView(heading);
        results = new ScrollView(this);
        results.setVerticalScrollBarEnabled(false);
        results.setClipChildren(false);
        results.setClipToPadding(false);
        results.setPadding(Ui.px(this, 20), Ui.px(this, 26), Ui.px(this, 20), Ui.px(this, 200));
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        results.addView(grid, new FrameLayout.LayoutParams(-1, -2));
        right.addView(results, new LinearLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams rightP = new FrameLayout.LayoutParams(-1, -1);
        rightP.leftMargin = Ui.px(this, 96 + 560 + 80);
        rightP.rightMargin = Ui.px(this, 76);
        rightP.topMargin = Ui.px(this, 160);
        root.addView(right, rightP);

        setContentView(root);
        lib = Library.current;
        if (lib == null) { // opened before the main screen finished looking: look now
            heading.setText("Looking for movies…");
            new Thread(() -> {
                Library l = Library.scan(this);
                main.post(() -> { lib = l; update(); });
            }, "scan").start();
        } else update();
    }

    // Six letters a row, a to z and 0 to 9, then space, delete, clear, and the microphone when the TV
    // has voice input.
    private LinearLayout keyboard() {
        LinearLayout keys = new LinearLayout(this);
        keys.setOrientation(LinearLayout.VERTICAL);
        String all = "abcdefghijklmnopqrstuvwxyz0123456789";
        int size = Ui.px(this, 80), gap = Ui.px(this, 12);
        View first = null;
        for (int r = 0; r < 6; r++) {
            LinearLayout row = new LinearLayout(this);
            for (int c = 0; c < 6; c++) {
                char ch = all.charAt(r * 6 + c);
                TextView k = key(String.valueOf(ch), () -> type(String.valueOf(ch)));
                if (first == null) first = k;
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(size, size);
                p.rightMargin = c < 5 ? gap : 0;
                p.bottomMargin = gap;
                row.addView(k, p);
            }
            keys.addView(row);
        }
        LinearLayout last = new LinearLayout(this);
        boolean voice = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).resolveActivity(getPackageManager()) != null;
        List<TextView> bottom = new ArrayList<>();
        bottom.add(key("space", () -> { if (query.length() > 0 && query.charAt(query.length() - 1) != ' ') type(" "); }));
        bottom.add(key("⌫", this::delete));
        bottom.add(key("clear", () -> { query.setLength(0); update(); }));
        if (voice) bottom.add(key("🎤", this::listen));
        int wide = (6 * size + 5 * gap - (bottom.size() - 1) * gap) / bottom.size();
        for (int i = 0; i < bottom.size(); i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(wide, size);
            p.rightMargin = i < bottom.size() - 1 ? gap : 0;
            last.addView(bottom.get(i), p);
        }
        keys.addView(last);
        firstKey = first;
        first.requestFocus();
        return keys;
    }

    private TextView key(String label, Runnable action) {
        TextView k = Ui.text(this, label.length() > 1 ? 24 : 34, Color.WHITE, true);
        k.setText(label);
        k.setGravity(Gravity.CENTER);
        k.setFocusable(true);
        k.setClickable(true);
        GradientDrawable off = new GradientDrawable();
        off.setCornerRadius(Ui.px(this, 10));
        off.setColor(0x22FFFFFF);
        GradientDrawable on = new GradientDrawable();
        on.setCornerRadius(Ui.px(this, 10));
        on.setColor(Color.WHITE);
        k.setBackground(off);
        k.setOnFocusChangeListener((v, has) -> {
            k.setBackground(has ? on : off);
            k.setTextColor(has ? 0xFF111111 : Color.WHITE);
        });
        k.setOnClickListener(v -> action.run());
        return k;
    }

    private void type(String s) {
        if (query.length() >= 40) return;
        query.append(s);
        update();
    }

    private void delete() {
        if (query.length() == 0) return;
        query.setLength(query.length() - 1);
        update();
    }

    private void listen() {
        try {
            startActivityForResult(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a title, a genre or an actor"), VOICE);
        } catch (ActivityNotFoundException e) {
            heading.setText("This TV has no voice input. Type with the letters instead.");
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != VOICE || result != RESULT_OK || data == null) return;
        ArrayList<String> said = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (said == null || said.isEmpty()) return;
        query.setLength(0);
        query.append(Library.norm(said.get(0)));
        update();
    }

    // A keyboard plugged into the TV, or a remote with letters: type straight in.
    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_DEL) { delete(); return true; }
        if (code == KeyEvent.KEYCODE_SEARCH) { listen(); return true; }
        int ch = e.getUnicodeChar();
        if (ch > 0 && (Character.isLetterOrDigit(ch) || ch == ' ') && code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) {
            type(String.valueOf((char) Character.toLowerCase(ch)));
            return true;
        }
        return super.onKeyDown(code, e);
    }

    // What's typed changed: the field at once; the suggestions and posters once typing pauses, so typing
    // a word quickly doesn't redo them for every letter.
    private final Runnable refresh = this::results;
    private final java.util.Map<Library.Title, FrameLayout> cards = new java.util.HashMap<>(); // made once, then reused

    private void update() {
        String q = query.toString();
        field.setText(q.isEmpty() ? "Titles, genres, people" : q + "_");
        field.setTextColor(q.isEmpty() ? Ui.FAINT : Color.WHITE);
        main.removeCallbacks(refresh);
        main.postDelayed(refresh, 220);
    }

    private void results() {
        main.removeCallbacks(refresh);
        String q = query.toString();
        if (lib == null) return;

        suggestions.removeAllViews();
        List<String> words = lib.suggestions(q, 5);
        if (!words.isEmpty()) {
            TextView label = Ui.text(this, 22, Ui.FAINT, true);
            label.setText(q.isEmpty() ? "GENRES" : "SUGGESTIONS");
            label.setPadding(0, 0, 0, Ui.px(this, 10));
            suggestions.addView(label);
        }
        for (String w : words) {
            TextView s = Ui.text(this, 28, Ui.DIM, false);
            s.setText(w);
            s.setFocusable(true);
            s.setClickable(true);
            s.setPadding(Ui.px(this, 14), Ui.px(this, 10), Ui.px(this, 14), Ui.px(this, 10));
            GradientDrawable on = new GradientDrawable();
            on.setCornerRadius(Ui.px(this, 8));
            on.setColor(0x33FFFFFF);
            s.setOnFocusChangeListener((v, has) -> {
                s.setBackground(has ? on : null);
                s.setTextColor(has ? Color.WHITE : Ui.DIM);
            });
            s.setOnClickListener(v -> {
                query.setLength(0);
                query.append(Library.norm(w));
                update();
                results();
                // The list it was in was just rebuilt: go on to the first title found, else the keyboard.
                View next = grid.getChildCount() > 0 ? ((LinearLayout) grid.getChildAt(0)).getChildAt(0) : firstKey;
                next.requestFocus();
            });
            suggestions.addView(s, new LinearLayout.LayoutParams(-2, -2));
        }

        List<Library.Title> found;
        if (q.trim().isEmpty()) {
            List<Library.Title> all = new ArrayList<>(lib.movies);
            all.addAll(lib.series);
            Collections.sort(all, (a, b) -> Long.compare(b.folder.lastModified(), a.folder.lastModified()));
            found = all.subList(0, Math.min(20, all.size()));
            heading.setText(lib.empty() ? "No movies or series found" : "Recently added");
        } else {
            found = lib.search(q);
            heading.setText(found.isEmpty() ? "Nothing matches \"" + q.trim() + "\"" : "Results for \"" + q.trim() + "\"");
        }
        for (int k = 0; k < grid.getChildCount(); k++) ((LinearLayout) grid.getChildAt(k)).removeAllViews(); // let the cards go, to reuse
        grid.removeAllViews();
        results.scrollTo(0, 0);
        int w = Ui.px(this, 186), h = Ui.px(this, 279), gap = Ui.px(this, 26);
        LinearLayout row = null;
        for (int i = 0; i < found.size(); i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, -2);
                rp.bottomMargin = gap + Ui.px(this, 10);
                grid.addView(row, rp);
            }
            Library.Title t = found.get(i);
            FrameLayout card = cards.get(t);
            if (card == null) {
                card = Ui.poster(this, t, w, h);
                card.setOnClickListener(v -> startActivity(new Intent(this, DetailsActivity.class).putExtra("folder", t.folder.key())));
                cards.put(t, card);
            }
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(w, h);
            cp.rightMargin = gap;
            row.addView(card, cp);
        }
    }
}
