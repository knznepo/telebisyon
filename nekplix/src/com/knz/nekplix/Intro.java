package com.knz.nekplix;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

// The intro when NEKPLIX opens: the logo grows out of the dark with a red glow while the intro sound
// plays (res/raw/intro.m4a, if it's there), then rushes towards you and the main screen appears behind
// it. The library loads meanwhile. Any key skips it.
final class Intro {
    private static final long SHOW_MS = 2700;
    private final Activity a;
    private final FrameLayout cover;
    private final ImageView logo;
    private final View glow;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaPlayer sound;
    private boolean done;

    static Intro show(Activity a, FrameLayout root) { return new Intro(a, root); }

    private Intro(Activity a, FrameLayout root) {
        this.a = a;
        cover = new FrameLayout(a);
        cover.setBackgroundColor(Color.BLACK);
        cover.setClickable(true);
        glow = new View(a);
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientRadius(Ui.px(a, 520));
        g.setColors(new int[]{0x99E50914, 0x33E50914, 0x00E50914});
        glow.setBackground(g);
        glow.setAlpha(0f);
        glow.setScaleX(0.5f);
        glow.setScaleY(0.5f);
        cover.addView(glow, new FrameLayout.LayoutParams(Ui.px(a, 1100), Ui.px(a, 1100), Gravity.CENTER));
        logo = new ImageView(a);
        logo.setImageResource(a.getResources().getIdentifier("logo", "drawable", a.getPackageName()));
        logo.setAdjustViewBounds(true);
        logo.setAlpha(0f);
        logo.setScaleX(0.72f);
        logo.setScaleY(0.72f);
        cover.addView(logo, new FrameLayout.LayoutParams(-2, Ui.px(a, 170), Gravity.CENTER));
        root.addView(cover, new FrameLayout.LayoutParams(-1, -1));

        android.view.animation.DecelerateInterpolator out = new android.view.animation.DecelerateInterpolator(2.2f);
        logo.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(700).setInterpolator(out).start();
        glow.animate().alpha(1f).scaleX(1.25f).scaleY(1.25f).setDuration(1500).setInterpolator(out).start();
        int id = a.getResources().getIdentifier("intro", "raw", a.getPackageName());
        if (id != 0) {
            try {
                sound = MediaPlayer.create(a, id);
                if (sound != null) {
                    sound.setOnCompletionListener(mp -> { mp.release(); if (sound == mp) sound = null; });
                    sound.start();
                }
            } catch (Exception ignored) {} // no sound: the logo on its own
        }
        main.postDelayed(this::finish, SHOW_MS);
    }

    boolean showing() { return !done; }

    // The logo rushes towards you, and the screen appears behind it. Also when a key skips it.
    void finish() {
        if (done) return;
        done = true;
        main.removeCallbacksAndMessages(null);
        android.view.animation.AccelerateInterpolator in = new android.view.animation.AccelerateInterpolator(1.6f);
        logo.animate().cancel();
        glow.animate().cancel();
        logo.animate().scaleX(7f).scaleY(7f).alpha(0f).setDuration(650).setInterpolator(in).start();
        glow.animate().scaleX(3f).scaleY(3f).alpha(0f).setDuration(650).setInterpolator(in).start();
        cover.animate().alpha(0f).setStartDelay(250).setDuration(450).withEndAction(() -> {
            ViewGroup up = (ViewGroup) cover.getParent();
            if (up != null) up.removeView(cover);
        }).start();
    }

    // Leaving the app: the sound stops with it.
    void stop() {
        if (sound == null) return;
        try { sound.stop(); sound.release(); } catch (Exception ignored) {}
        sound = null;
    }
}
