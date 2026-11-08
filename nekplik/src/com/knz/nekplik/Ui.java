package com.knz.nekplik;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

// The look every screen shares, drawn in code: sizes are for a 1920-wide TV and scaled to the real one.
final class Ui {
    static final int RED = 0xFFE50914, DIM = 0xB3FFFFFF, FAINT = 0x80FFFFFF, BG = 0xFF0E0A0C;

    static int px(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().widthPixels / 1920f); }

    static TextView text(Context c, float size, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(c, size));
        t.setTextColor(color);
        t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        return t;
    }

    // The background picture across the whole screen, darkened from the left and the bottom so the
    // text and rows over it stay readable.
    static ImageView backdrop(Activity a, FrameLayout root) {
        ImageView back = new ImageView(a);
        back.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(back, new FrameLayout.LayoutParams(-1, -1));
        View left = new View(a);
        left.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xF20E0A0C, 0xC70E0A0C, 0x5C0E0A0C, 0x000E0A0C}));
        root.addView(left, new FrameLayout.LayoutParams(-1, -1));
        View bottom = new View(a);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xFF0E0A0C, 0xCC0E0A0C, 0x000E0A0C}));
        root.addView(bottom, new FrameLayout.LayoutParams(-1, a.getResources().getDisplayMetrics().heightPixels * 2 / 3, Gravity.BOTTOM));
        return back;
    }

    // A pill button: white when focused, see-through glass otherwise.
    static TextView button(Context c, String label) {
        TextView b = text(c, 26, Color.WHITE, true);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setClickable(true);
        b.setPadding(px(c, 40), px(c, 18), px(c, 40), px(c, 18));
        GradientDrawable off = new GradientDrawable();
        off.setCornerRadius(px(c, 999));
        off.setColor(0x33FFFFFF);
        GradientDrawable on = new GradientDrawable();
        on.setCornerRadius(px(c, 999));
        on.setColor(Color.WHITE);
        b.setBackground(off);
        b.setOnFocusChangeListener((v, has) -> {
            b.setBackground(has ? on : off);
            b.setTextColor(has ? 0xFF111111 : Color.WHITE);
        });
        return b;
    }

    // The red NEKPLIK wordmark at the top of each screen: a picture (art.py draws it), since TVs don't
    // have its tall condensed font.
    static ImageView brand(Context c) {
        ImageView logo = new ImageView(c);
        logo.setImageResource(c.getResources().getIdentifier("logo", "drawable", c.getPackageName())); // no R class: build.sh skips it
        logo.setAdjustViewBounds(true);
        logo.setMaxHeight(px(c, 54));
        logo.setScaleType(ImageView.ScaleType.FIT_START);
        return logo;
    }

    // A poster card: the title's poster (its name until there is one), growing a little with a white
    // edge when focused.
    static FrameLayout poster(Context c, Library.Title t, int w, int h) {
        int r = px(c, 12);
        FrameLayout card = new FrameLayout(c);
        card.setFocusable(true);
        card.setClickable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(r);
        bg.setColor(0xFF2A2326);
        card.setBackground(bg);
        card.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); }
        });
        card.setClipToOutline(true);
        TextView name = text(c, 22, Color.WHITE, true);
        name.setText(t.title);
        name.setGravity(Gravity.CENTER);
        name.setPadding(px(c, 10), 0, px(c, 10), 0);
        card.addView(name, new FrameLayout.LayoutParams(-1, -1));
        ImageView img = new ImageView(c);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        Images.into(img, t.poster, w, h);
        GradientDrawable edge = new GradientDrawable();
        edge.setCornerRadius(r);
        edge.setStroke(px(c, 4), Color.WHITE);
        card.setOnFocusChangeListener((v, has) -> {
            card.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).setDuration(150).start();
            card.setForeground(has ? edge : null);
        });
        return card;
    }

    // "1:02:03" or "4:05".
    static String time(long ms) {
        long s = ms / 1000, h = s / 3600, m = s / 60 % 60;
        return h > 0 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, s % 60)
                : String.format(java.util.Locale.ROOT, "%d:%02d", m, s % 60);
    }

    // ---- Shared by the featured top and the details page, in the web version's style ----

    // The details under a title: the score in green, the rating in a box, the year, the length, HD for a
    // 720p or better file, and the first genres. "first" goes before them (an episode's number and name).
    static void facts(Context c, LinearLayout into, Library.Title t, String first) {
        into.removeAllViews();
        if (first != null) fact(c, into, first, Color.WHITE, true, false);
        if (t.score > 0) fact(c, into, "★ " + (t.score == Math.floor(t.score) ? String.valueOf((int) t.score) : String.valueOf(t.score)), 0xFF46D369, true, false);
        if (t.year > 0) fact(c, into, String.valueOf(t.year), DIM, false, false);
        if (t.rating != null) fact(c, into, t.rating, DIM, false, true);
        if (t.series) fact(c, into, t.seasons.size() == 1 ? t.episodes().size() + " episodes" : t.seasons.size() + " seasons", DIM, false, false);
        else if (t.runtime > 0) fact(c, into, t.runtime >= 60 ? t.runtime / 60 + "h " + t.runtime % 60 + "m" : t.runtime + "m", DIM, false, false);
        if (t.movie != null && t.movie.file.name().matches("(?i).*(720|1080|2160|4k).*")) fact(c, into, "HD", DIM, true, true);
        if (!t.genres.isEmpty()) fact(c, into, android.text.TextUtils.join(", ", t.genres.subList(0, Math.min(3, t.genres.size()))), DIM, false, false);
        into.setVisibility(into.getChildCount() == 0 ? View.GONE : View.VISIBLE);
    }

    private static void fact(Context c, LinearLayout into, String s, int color, boolean bold, boolean boxed) {
        TextView f = text(c, boxed ? 22 : 26, color, bold);
        f.setText(s);
        f.setShadowLayer(px(c, 6), 0, 0, 0x80000000);
        if (boxed) {
            GradientDrawable box = new GradientDrawable();
            box.setCornerRadius(px(c, 4));
            box.setStroke(Math.max(1, px(c, 2)), 0x8CFFFFFF);
            f.setBackground(box);
            f.setPadding(px(c, 9), px(c, 3), px(c, 9), px(c, 3));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = px(c, 22);
        into.addView(f, lp);
    }

    // A title as on the web version: bold, a little tighter, with a soft shadow to read over pictures.
    static TextView title(Context c, float size) {
        TextView t = text(c, size, Color.WHITE, true);
        t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        t.setLetterSpacing(-0.02f);
        t.setShadowLayer(px(c, 24), 0, px(c, 2), 0x73000000);
        return t;
    }

    // The web version's buttons, with an icon: the one with the focus white and a little bigger, the
    // others see-through grey. onFocus runs when it gets the focus (or null).
    static TextView bigButton(Context c, String label, int icon, Runnable onFocus) {
        TextView b = text(c, 28, Color.WHITE, true);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setClickable(true);
        b.setMinHeight(px(c, 70));
        b.setPadding(px(c, 30), 0, px(c, 38), 0);
        b.setCompoundDrawablePadding(px(c, 14));
        int r = px(c, 8);
        GradientDrawable on = round(Color.WHITE, r), off = round(0x9E6D6D6E, r);
        Icon i = new Icon(icon, px(c, 30));
        b.setCompoundDrawables(i, null, null, null);
        b.setBackground(off);
        b.setOnFocusChangeListener((v, has) -> {
            b.setBackground(has ? on : off);
            b.setTextColor(has ? 0xFF111111 : Color.WHITE);
            i.color = has ? 0xFF111111 : Color.WHITE;
            i.invalidateSelf();
            b.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(200)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(2f)).start();
            if (has && onFocus != null) onFocus.run();
        });
        return b;
    }

    static GradientDrawable round(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(radius);
        g.setColor(color);
        return g;
    }

    // A background slowly zooming out and drifting, over 16 seconds. Stops the one it replaces.
    static android.animation.Animator drift(View v, android.animation.Animator old) {
        if (old != null) old.cancel();
        android.animation.ObjectAnimator a = android.animation.ObjectAnimator.ofPropertyValuesHolder(v,
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1.12f, 1f),
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.12f, 1f),
                android.animation.PropertyValuesHolder.ofFloat(View.TRANSLATION_X, v.getResources().getDisplayMetrics().widthPixels * 0.012f, 0f));
        a.setDuration(16000);
        a.setInterpolator(new android.view.animation.LinearInterpolator());
        a.start();
        return a;
    }

    // Play's triangle, More info's circled i, Start over's turning arrow: in the button's text colour.
    static final class Icon extends android.graphics.drawable.Drawable {
        static final int PLAY = 0, INFO = 1, RESTART = 2;
        final int kind, size;
        int color = Color.WHITE;
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        Icon(int kind, int size) {
            this.kind = kind;
            this.size = size;
            setBounds(0, 0, size, size);
        }

        @Override
        public void draw(android.graphics.Canvas c) {
            paint.setColor(color);
            float s = size;
            if (kind == PLAY) {
                paint.setStyle(android.graphics.Paint.Style.FILL);
                android.graphics.Path p = new android.graphics.Path();
                p.moveTo(s * .2f, s * .1f);
                p.lineTo(s * .9f, s * .5f);
                p.lineTo(s * .2f, s * .9f);
                p.close();
                c.drawPath(p, paint);
                return;
            }
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(s * .09f);
            paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            if (kind == INFO) {
                c.drawCircle(s / 2, s / 2, s * .42f, paint);
                c.drawLine(s / 2, s * .45f, s / 2, s * .72f, paint);
                c.drawPoint(s / 2, s * .3f, paint);
            } else {
                android.graphics.RectF o = new android.graphics.RectF(s * .12f, s * .12f, s * .88f, s * .88f);
                c.drawArc(o, 200, 290, false, paint);
                paint.setStyle(android.graphics.Paint.Style.FILL);
                android.graphics.Path p = new android.graphics.Path(); // the arrowhead, at the arc's start
                p.moveTo(s * .02f, s * .26f);
                p.lineTo(s * .32f, s * .22f);
                p.lineTo(s * .14f, s * .48f);
                p.close();
                c.drawPath(p, paint);
            }
        }

        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(android.graphics.ColorFilter f) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
}
