package com.knz.nekplik;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

// The intro when NEKPLIK opens, on plain black while the intro sound plays (res/raw/intro.m4a, if it's
// there): the whole logo fades in, then in one smooth movement comes closer, slowly at first and faster
// and faster, until its letters fly past the edges of the screen and the main screen fades in behind
// them. The library loads meanwhile. Any key hurries it along.
//
// Smooth because the zoom is a single movement that starts from standing still (no change of speed
// between a "push" and a "rush"), it's even in how big the logo looks (the scale grows exponentially),
// the logo picture is large (drawable-nodpi/intro_logo.png, from art.py) so it stays sharp, and the
// screen draws it on its graphics chip while it moves.
final class Intro {
    private static final long APPEAR_MS = 800, ZOOM_MS = 2700;
    private static final float END_SCALE = 16f;
    private static final float FADE_FROM = 0.8f; // how far into the zoom the screen starts to show through
    private final FrameLayout cover;
    private final ImageView logo;
    private MediaPlayer sound;
    private ValueAnimator zoom;
    private boolean done;

    // Whether this process has shown a screen yet. Android closes NEKPLIK in the background to free
    // memory (Sony TVs often), then rebuilds the last screen when it's opened again, as if returning to
    // it: without this, that opening had no intro. The other screens set it, so returning to a
    // rebuilt film or player doesn't play it.
    static boolean opened;

    static Intro show(Activity a, FrameLayout root) { opened = true; return new Intro(a, root); }

    private Intro(Activity a, FrameLayout root) {
        cover = new FrameLayout(a);
        cover.setBackgroundColor(Color.BLACK);
        cover.setClickable(true);
        logo = new ImageView(a);
        int big = a.getResources().getIdentifier("intro_logo", "drawable", a.getPackageName());
        logo.setImageResource(big != 0 ? big : a.getResources().getIdentifier("logo", "drawable", a.getPackageName()));
        logo.setAdjustViewBounds(true);
        logo.setAlpha(0f);
        logo.setScaleX(0.82f);
        logo.setScaleY(0.82f);
        logo.setLayerType(View.LAYER_TYPE_HARDWARE, null); // moved by the graphics chip, not redrawn each frame
        cover.addView(logo, new FrameLayout.LayoutParams(-2, Ui.px(a, 170), Gravity.CENTER));
        root.addView(cover, new FrameLayout.LayoutParams(-1, -1));

        // In: it fades up and settles, coming to rest just as the zoom begins from rest.
        logo.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(APPEAR_MS)
                .setInterpolator(new DecelerateInterpolator(1.6f)).withEndAction(() -> zoom(1f, ZOOM_MS, 3)).start();

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
    }

    // From "from" to END_SCALE: slow at first, then faster and faster (an ease-in on the logarithm of the
    // scale, so each moment it grows by a steadily larger share). The screen shows through near the end.
    private void zoom(float from, long ms, int power) {
        if (zoom != null) zoom.cancel();
        logo.animate().cancel();
        logo.setPivotX(logo.getWidth() / 2f);
        logo.setPivotY(logo.getHeight() / 2f);
        float lnFrom = (float) Math.log(from), lnTo = (float) Math.log(END_SCALE);
        float startAlpha = cover.getAlpha();
        zoom = ValueAnimator.ofFloat(0f, 1f);
        zoom.setDuration(ms);
        zoom.setInterpolator(null); // linear time: the easing is below
        zoom.addUpdateListener(v -> {
            float t = (float) v.getAnimatedValue();
            float eased = power == 3 ? t * t * t : t * t; // a key's hurry starts already moving
            float s = (float) Math.exp(lnFrom + (lnTo - lnFrom) * eased);
            logo.setScaleX(s);
            logo.setScaleY(s);
            float f = t < FADE_FROM ? 0f : (t - FADE_FROM) / (1f - FADE_FROM);
            cover.setAlpha(startAlpha * (1f - f * f * (3f - 2f * f))); // a smooth fade (smoothstep)
        });
        zoom.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator x) {
                done = true;
                logo.setLayerType(View.LAYER_TYPE_NONE, null);
                ViewGroup up = (ViewGroup) cover.getParent();
                if (up != null) up.removeView(cover);
            }
        });
        zoom.start();
    }

    boolean showing() { return !done; }

    // A key: on into the fast part of the zoom from wherever it is, without a jump.
    void finish() {
        if (done) return;
        done = true;
        float s = logo.getScaleX();
        logo.setAlpha(1f);
        zoom(Math.max(1f, s), 900, 2);
    }

    // Leaving the app: the sound stops with it.
    void stop() {
        if (sound == null) return;
        try { sound.stop(); sound.release(); } catch (Exception ignored) {}
        sound = null;
    }
}
