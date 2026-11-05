package com.knz.flixnet;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.VideoView;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Plays a movie, or a series from an episode on into the next ones, with the TV's own video player
// (so what plays is what the TV's hardware can: most MP4 and MKV with H.264 or H.265).
//
// OK or play/pause pauses. Left and right skip 10 seconds (further while held); rewind and fast
// forward 30. Down turns subtitles on or off, up shows where it is. Back stops, and it remembers where.
// After an episode, the next one starts in 5 seconds (OK: now, Back: not at all).
public class PlayerActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private VideoView video;
    private TextView subtitle, heading, sub, clock, hint, next, problem;
    private View controls, top;
    private Bar bar;
    private List<Library.Video> list = new ArrayList<>();
    private int index;
    private boolean resume, prepared, subtitlesOn = true, finishing;
    private Library.Title t;
    private List<Cue> cues = Collections.emptyList();
    private long lastSaved, hideAt;
    private int countdown;

    static Intent intent(Context c, Library.Title t, Library.Video v, boolean resume) {
        return new Intent(c, PlayerActivity.class).putExtra("folder", t.folder.getAbsolutePath())
                .putExtra("video", v.file.getAbsolutePath()).putExtra("resume", resume);
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        t = MainActivity.find(getIntent().getStringExtra("folder"));
        if (t == null) { finish(); return; }
        list = t.series ? t.episodes() : Collections.singletonList(t.movie);
        String path = getIntent().getStringExtra("video");
        for (int i = 0; i < list.size(); i++) if (list.get(i).file.getAbsolutePath().equals(path)) index = i;
        resume = getIntent().getBooleanExtra("resume", false);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        video = new VideoView(this);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        subtitle = Ui.text(this, 44, Color.WHITE, true);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setShadowLayer(Ui.px(this, 6), 0, Ui.px(this, 2), Color.BLACK);
        subtitle.setLineSpacing(Ui.px(this, 6), 1f);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        sl.leftMargin = sl.rightMargin = Ui.px(this, 160);
        sl.bottomMargin = Ui.px(this, 90);
        root.addView(subtitle, sl);

        // The top: what's playing.
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(Ui.px(this, 96), Ui.px(this, 60), Ui.px(this, 96), Ui.px(this, 120));
        head.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xCC000000, 0x00000000}));
        heading = Ui.text(this, 46, Color.WHITE, true);
        sub = Ui.text(this, 28, Ui.DIM, false);
        sub.setPadding(0, Ui.px(this, 12), 0, 0);
        head.addView(heading);
        head.addView(sub);
        top = head;
        root.addView(head, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // The bottom: how far along, and the keys.
        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.VERTICAL);
        foot.setPadding(Ui.px(this, 96), Ui.px(this, 140), Ui.px(this, 96), Ui.px(this, 60));
        foot.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xE6000000, 0x00000000}));
        bar = new Bar(this);
        foot.addView(bar, new LinearLayout.LayoutParams(-1, Ui.px(this, 14)));
        LinearLayout line = new LinearLayout(this);
        line.setPadding(0, Ui.px(this, 18), 0, 0);
        clock = Ui.text(this, 26, Color.WHITE, true);
        hint = Ui.text(this, 22, Ui.FAINT, false);
        hint.setGravity(Gravity.END);
        line.addView(clock, new LinearLayout.LayoutParams(0, -2, 1));
        line.addView(hint, new LinearLayout.LayoutParams(0, -2, 2));
        foot.addView(line);
        controls = foot;
        root.addView(foot, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        next = Ui.text(this, 30, Color.WHITE, true);
        next.setPadding(Ui.px(this, 36), Ui.px(this, 26), Ui.px(this, 36), Ui.px(this, 26));
        GradientDrawable card = new GradientDrawable();
        card.setCornerRadius(Ui.px(this, 18));
        card.setColor(0xE6201A1D);
        next.setBackground(card);
        next.setVisibility(View.GONE);
        FrameLayout.LayoutParams nl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        nl.rightMargin = nl.bottomMargin = Ui.px(this, 96);
        root.addView(next, nl);

        problem = Ui.text(this, 34, Color.WHITE, false);
        problem.setGravity(Gravity.CENTER);
        problem.setVisibility(View.GONE);
        root.addView(problem, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        video.setOnPreparedListener(mp -> {
            prepared = true;
            Progress.Entry p = Progress.get(this, current().file);
            if (resume && p != null && p.resumable()) video.seekTo((int) p.position);
            video.start();
            showControls();
        });
        video.setOnCompletionListener(mp -> ended());
        video.setOnErrorListener((mp, what, extra) -> {
            problem.setText("This TV can't play this video (" + Library.ext(current().file).toUpperCase(java.util.Locale.ROOT) + ").\n"
                    + "Try an MP4 or MKV with H.264 or H.265 video.");
            problem.setVisibility(View.VISIBLE);
            main.postDelayed(this::finish, 6000);
            return true;
        });
        load();
        main.post(tick);
    }

    private Library.Video current() { return list.get(index); }

    private void load() {
        prepared = false;
        Library.Video v = current();
        heading.setText(t.title);
        sub.setText(t.series ? "S" + v.season + ":E" + v.number + "  " + v.title : t.meta());
        keysHint();
        cues = Collections.emptyList();
        subtitle.setText("");
        if (!v.subtitles.isEmpty()) {
            File srt = v.subtitles.get(0);
            new Thread(() -> {
                List<Cue> c = parse(srt);
                main.post(() -> { if (current() == v) cues = c; });
            }).start();
        }
        video.setVideoPath(v.file.getAbsolutePath());
    }

    // Four times a second: the subtitle on screen, the bar and the clock, and every few seconds, where it is.
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (prepared) {
                long pos = video.getCurrentPosition(), dur = video.getDuration();
                String text = "";
                if (subtitlesOn) for (Cue c : cues) if (pos >= c.start && pos <= c.end) { text = c.text; break; }
                if (!text.contentEquals(subtitle.getText())) subtitle.setText(text);
                bar.set(dur > 0 ? (float) pos / dur : 0);
                clock.setText(Ui.time(pos) + "  /  " + Ui.time(Math.max(0, dur)));
                if (System.currentTimeMillis() - lastSaved > 5000 && video.isPlaying()) save();
            }
            boolean show = !prepared || !video.isPlaying() || System.currentTimeMillis() < hideAt;
            float a = show ? 1f : 0f;
            if (controls.getAlpha() != a) {
                controls.animate().alpha(a).setDuration(250).start();
                top.animate().alpha(a).setDuration(250).start();
            }
            main.postDelayed(this, 250);
        }
    };

    private void showControls() { hideAt = System.currentTimeMillis() + 4000; }

    private void save() {
        if (!prepared) return;
        lastSaved = System.currentTimeMillis();
        Progress.save(this, current().file, video.getCurrentPosition(), video.getDuration());
    }

    // The end: watched. The next episode in 5 seconds, or back to where this was opened from.
    private void ended() {
        Progress.save(this, current().file, video.getDuration(), video.getDuration());
        if (index + 1 >= list.size()) { finish(); return; }
        countdown = 5;
        Library.Video n = list.get(index + 1);
        next.setVisibility(View.VISIBLE);
        Runnable count = new Runnable() {
            @Override
            public void run() {
                if (next.getVisibility() != View.VISIBLE) return;
                if (countdown <= 0) { playNext(); return; }
                next.setText("Next:  S" + n.season + ":E" + n.number + "  " + n.title + "\nStarting in " + countdown + "  ·  OK to start now");
                countdown--;
                main.postDelayed(this, 1000);
            }
        };
        main.post(count);
    }

    private void playNext() {
        next.setVisibility(View.GONE);
        index++;
        resume = true;
        load();
    }

    private void seek(int by) {
        if (!prepared) return;
        int to = Math.max(0, Math.min(video.getDuration() - 1000, video.getCurrentPosition() + by));
        video.seekTo(to);
        showControls();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (next.getVisibility() == View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) { playNext(); return true; }
            if (code == KeyEvent.KEYCODE_BACK) { finish(); return true; }
            return true;
        }
        int held = e.getRepeatCount();
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_HEADSETHOOK:
                if (held > 0) return true;
                if (video.isPlaying()) { video.pause(); save(); } else video.start();
                showControls();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY: video.start(); showControls(); return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE: video.pause(); save(); showControls(); return true;
            // Held: further each time, up to a minute a step.
            case KeyEvent.KEYCODE_DPAD_LEFT: seek(-Math.min(60000, 10000 * (1 + held / 4))); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: seek(Math.min(60000, 10000 * (1 + held / 4))); return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND: seek(-30000); return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: seek(30000); return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT: if (index + 1 < list.size()) { save(); playNext(); } return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (!current().subtitles.isEmpty() && held == 0) {
                    subtitlesOn = !subtitlesOn;
                    if (!subtitlesOn) subtitle.setText("");
                    hint.setText(subtitlesOn ? "Subtitles on" : "Subtitles off");
                    showControls();
                    main.postDelayed(this::keysHint, 2500);
                } else showControls();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP: showControls(); return true;
        }
        return super.onKeyDown(code, e);
    }

    private void keysHint() {
        hint.setText("OK  pause     ◀ ▶  skip" + (current().subtitles.isEmpty() ? "" : "     ▼  subtitles"));
    }

    @Override
    protected void onPause() {
        super.onPause();
        save();
        if (video.isPlaying()) video.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
        if (video != null) video.stopPlayback();
    }

    // A thin bar: the whole length, and the part played in gold.
    private static final class Bar extends View {
        private final Paint back = new Paint(Paint.ANTI_ALIAS_FLAG), done = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float at;

        Bar(Context c) {
            super(c);
            back.setColor(0x4DFFFFFF);
            done.setColor(Ui.GOLD);
        }

        void set(float f) {
            if (Math.abs(f - at) < 0.0005f) return;
            at = f;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float h = getHeight(), r = h / 2;
            c.drawRoundRect(new RectF(0, 0, getWidth(), h), r, r, back);
            c.drawRoundRect(new RectF(0, 0, Math.max(h, getWidth() * at), h), r, r, done);
        }
    }

    // One subtitle: shown from start to end (milliseconds).
    private static final class Cue {
        long start, end;
        String text;
    }

    // An .srt file: numbered blocks of "00:01:02,500 --> 00:01:05,000" and the lines to show. Formatting
    // tags (<i>, {\an8}) are dropped. Read as UTF-8, or as Windows Latin if that doesn't fit.
    private static List<Cue> parse(File f) {
        List<Cue> out = new ArrayList<>();
        try {
            String s = Library.read(f);
            if (s.indexOf('�') >= 0) s = new String(bytes(f), "windows-1252");
            Pattern time = Pattern.compile("(\\d+):(\\d+):(\\d+)[,.](\\d+)\\s*-->\\s*(\\d+):(\\d+):(\\d+)[,.](\\d+)");
            for (String block : s.replace("\r", "").split("\n\\s*\n")) {
                String[] lines = block.trim().split("\n");
                for (int i = 0; i < lines.length; i++) {
                    Matcher m = time.matcher(lines[i]);
                    if (!m.find()) continue;
                    Cue c = new Cue();
                    c.start = ms(m, 1);
                    c.end = ms(m, 5);
                    StringBuilder text = new StringBuilder();
                    for (int j = i + 1; j < lines.length; j++) {
                        if (text.length() > 0) text.append('\n');
                        text.append(lines[j].replaceAll("<[^>]*>|\\{[^}]*\\}", "").trim());
                    }
                    c.text = text.toString();
                    out.add(c);
                    break;
                }
            }
        } catch (Exception ignored) {} // unreadable: no subtitles
        return out;
    }

    private static byte[] bytes(File f) throws Exception {
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static long ms(Matcher m, int g) {
        String frac = (m.group(g + 3) + "00").substring(0, 3);
        return ((Long.parseLong(m.group(g)) * 60 + Long.parseLong(m.group(g + 1))) * 60 + Long.parseLong(m.group(g + 2))) * 1000 + Long.parseLong(frac);
    }
}
