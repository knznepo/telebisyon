package com.knz.nekplix;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Plays a movie, or a series from an episode on into the next ones, with the TV's own video player
// (so what plays is what the TV's hardware can: most MP4 and MKV with H.264 or H.265), from a drive or
// streamed from a computer sharing it.
//
// OK or play/pause pauses. Left and right skip 10 seconds (further while held); rewind and fast
// forward 30. Down goes through the subtitles (off, then each language), up shows where it is. Back
// stops, and it remembers where. The subtitle choice is remembered too: off stays off, and the language
// last picked comes first next time.
// After an episode, the next one starts in 5 seconds (OK: now, Back: not at all).
public class PlayerActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private VideoView video;
    private TextView subtitle, heading, sub, clock, hint, next, problem;
    private View controls, top;
    private Bar bar;
    private List<Library.Video> list = new ArrayList<>();
    private int index;
    private boolean resume, prepared, finishing;
    private int subtitleAt = -1; // which of the choices shows, -1 for none
    private final List<String> subtitleNames = new ArrayList<>();
    // Each choice: an .srt beside the video (srt set), or a track inside it (track, its index in the
    // TV's player). The ones inside come in once the video is ready.
    private final List<Node> subtitleFiles = new ArrayList<>();
    private final List<Integer> subtitleTracks = new ArrayList<>();
    private android.media.MediaPlayer player;
    private int trackOn = -1; // the track inside the video selected now, -1 for none
    private String trackText = "";
    private Library.Title t;
    private List<Cue> cues = Collections.emptyList();
    private long lastSaved, hideAt;
    private int countdown;
    private float subtitleLift = 1; // where the subtitles were last moved to (1: not yet)

    static Intent intent(Context c, Library.Title t, Library.Video v, boolean resume) {
        return new Intent(c, PlayerActivity.class).putExtra("folder", t.folder.key())
                .putExtra("video", v.file.key()).putExtra("resume", resume);
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        t = MainActivity.find(getIntent().getStringExtra("folder"));
        if (t == null) { finish(); return; }
        list = t.series ? t.episodes() : Collections.singletonList(t.movie);
        String path = getIntent().getStringExtra("video");
        for (int i = 0; i < list.size(); i++) if (list.get(i).file.key().equals(path)) index = i;
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
        nl.rightMargin = Ui.px(this, 96);
        nl.bottomMargin = Ui.px(this, 200); // above the bar and the clock
        root.addView(next, nl);

        problem = Ui.text(this, 34, Color.WHITE, false);
        problem.setGravity(Gravity.CENTER);
        problem.setVisibility(View.GONE);
        root.addView(problem, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        video.setOnPreparedListener(mp -> {
            prepared = true;
            player = mp;
            addInsideSubtitles(mp);
            Progress.Entry p = Progress.get(this, current().file);
            if (resume && p != null && p.resumable()) video.seekTo((int) p.position);
            video.start();
            showControls();
        });
        video.setOnCompletionListener(mp -> ended());
        video.setOnErrorListener((mp, what, extra) -> {
            String ext = Library.ext(current().file);
            problem.setText("avi".equals(ext)
                    ? "This TV can't open AVI files.\nTurn it into an MKV with avi2mkv.sh: it takes a minute and keeps the quality."
                    : "This TV can't play this video (" + ext.toUpperCase(java.util.Locale.ROOT) + ").\n"
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
        // The .srt files beside it, each named by its language, else "Subtitles" (numbered if more).
        subtitleNames.clear();
        subtitleFiles.clear();
        subtitleTracks.clear();
        player = null;
        trackOn = -1;
        for (int i = 0; i < v.subtitles.size(); i++) {
            String lang = Library.language(v.subtitles.get(i));
            subtitleNames.add(lang != null ? lang : v.subtitles.size() == 1 ? "Subtitles" : "Subtitles " + (i + 1));
            subtitleFiles.add(v.subtitles.get(i));
            subtitleTracks.add(-1);
        }
        showSubtitles(chosen());
        // A path on a drive, or a link to the computer sharing it (share.py serves any part asked for,
        // so skipping around works).
        if (v.file.remote()) video.setVideoURI(android.net.Uri.parse(v.file.uri()));
        else video.setVideoPath(v.file.uri());
    }

    // Four times a second: the subtitle on screen, the bar and the clock, and every few seconds, where it is.
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (prepared) {
                long pos = video.getCurrentPosition(), dur = video.getDuration();
                String text = "";
                if (trackOn >= 0) text = trackText;
                else if (subtitleAt >= 0) for (Cue c : cues) if (pos >= c.start && pos <= c.end) { text = c.text; break; }
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
            // Subtitles go up out of the way while the bar shows, and back down after.
            float lift = show ? -Ui.px(PlayerActivity.this, 150) : 0;
            if (lift != subtitleLift) {
                subtitleLift = lift;
                subtitle.animate().translationY(lift).setDuration(250).start();
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
                if (!subtitleNames.isEmpty() && held == 0) {
                    // Off, then each one in turn, then off again. Remembered for the next video.
                    int at = subtitleAt + 1 >= subtitleNames.size() ? -1 : subtitleAt + 1;
                    showSubtitles(at);
                    getSharedPreferences(PREFS, 0).edit().putString(PREF_SUBTITLES, at < 0 ? "off" : subtitleNames.get(at)).apply();
                    hint.setText(at < 0 ? "Subtitles off" : subtitleNames.size() == 1 ? "Subtitles on" : "Subtitles: " + subtitleNames.get(at));
                    showControls();
                    main.removeCallbacks(keysHintLater);
                    main.postDelayed(keysHintLater, 2500);
                } else showControls();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP: showControls(); return true;
        }
        return super.onKeyDown(code, e);
    }

    private static final String PREFS = "player", PREF_SUBTITLES = "subtitles";
    private final Runnable keysHintLater = this::keysHint;

    // Shows the video's subtitle at, read off the main thread (-1: none).
    private void showSubtitles(int at) {
        subtitleAt = at;
        cues = Collections.emptyList();
        subtitle.setText("");
        trackText = "";
        if (player != null && trackOn >= 0) {
            try { player.deselectTrack(trackOn); } catch (Exception ignored) {}
        }
        trackOn = -1;
        if (at < 0) return;
        if (subtitleTracks.get(at) >= 0) { // inside the video: the TV's player reads it
            if (player == null) return;
            try {
                player.selectTrack(subtitleTracks.get(at));
                trackOn = subtitleTracks.get(at);
            } catch (Exception e) {
                subtitleAt = -1; // this TV can't show that one
            }
            return;
        }
        Library.Video v = current();
        Node srt = subtitleFiles.get(at);
        new Thread(() -> {
            List<Cue> c = parse(srt);
            main.post(() -> { if (current() == v && subtitleAt == at) cues = c; });
        }).start();
    }

    // As last chosen: off, or the language picked (beside or inside the video), else the first.
    private int chosen() {
        String last = getSharedPreferences(PREFS, 0).getString(PREF_SUBTITLES, null);
        if (subtitleNames.isEmpty() || "off".equals(last)) return -1;
        if (last != null) {
            int at = subtitleNames.indexOf(last);
            if (at >= 0) return at;
            for (int i = 0; i < subtitleNames.size(); i++) if (subtitleNames.get(i).startsWith(last.replace(" (in video)", ""))) return i;
        }
        return 0;
    }

    // Subtitles inside the video (an MKV's or MP4's text tracks), as the TV's player finds them: added
    // after the .srt files, named by their language, "English (in video)". With none beside it, one of
    // these comes on if subtitles were on.
    private void addInsideSubtitles(android.media.MediaPlayer mp) {
        android.media.MediaPlayer.TrackInfo[] tracks;
        try { tracks = mp.getTrackInfo(); } catch (Exception e) { return; }
        int found = 0;
        for (int i = 0; i < tracks.length; i++) {
            int type = tracks[i].getTrackType();
            if (type != android.media.MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT
                    && type != android.media.MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE) continue;
            String code = tracks[i].getLanguage(), lang = null;
            if (code != null && !code.isEmpty() && !code.equals("und")) {
                lang = new java.util.Locale(code).getDisplayLanguage();
                if (lang.equalsIgnoreCase(code)) lang = null;
            }
            subtitleNames.add((lang != null ? lang : "Subtitles") + " (in video)");
            subtitleFiles.add(null);
            subtitleTracks.add(i);
            found++;
        }
        android.util.Log.i("NekplixPlayer", tracks.length + " tracks, " + found + " subtitles inside");
        if (found == 0) return;
        mp.setOnTimedTextListener((m, text) -> trackText = text == null || text.getText() == null ? "" : text.getText().trim());
        if (subtitleAt < 0 && subtitleFiles.get(0) == null) showSubtitles(chosen()); // only ones inside: as chosen
        keysHint();
    }

    private void keysHint() {
        hint.setText("OK  pause     ◀ ▶  skip" + (subtitleNames.isEmpty() ? "" : "     ▼  subtitles"));
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

    // A thin bar: the whole length, and the part played in red.
    private static final class Bar extends View {
        private final Paint back = new Paint(Paint.ANTI_ALIAS_FLAG), done = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float at;

        Bar(Context c) {
            super(c);
            back.setColor(0x4DFFFFFF);
            done.setColor(Ui.RED);
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
    private static List<Cue> parse(Node f) {
        List<Cue> out = new ArrayList<>();
        try {
            byte[] raw = f.bytes(4 * 1024 * 1024);
            String s = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
            if (s.indexOf('\uFFFD') >= 0) s = new String(raw, "windows-1252");
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

    private static long ms(Matcher m, int g) {
        String frac = (m.group(g + 3) + "00").substring(0, 3);
        return ((Long.parseLong(m.group(g)) * 60 + Long.parseLong(m.group(g + 1))) * 60 + Long.parseLong(m.group(g + 2))) * 1000 + Long.parseLong(frac);
    }
}
