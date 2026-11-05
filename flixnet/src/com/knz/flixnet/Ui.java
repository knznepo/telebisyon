package com.knz.flixnet;

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
import android.widget.TextView;

// The look every screen shares, drawn in code: sizes are for a 1920-wide TV and scaled to the real one.
final class Ui {
    static final int GOLD = 0xFFFFB347, DIM = 0xB3FFFFFF, FAINT = 0x80FFFFFF, BG = 0xFF0E0A0C;

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
        left.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xF20E0A0C, 0xB30E0A0C, 0x000E0A0C}));
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

    // The small FLIXNET wordmark at the top of each screen.
    static TextView brand(Context c) {
        TextView t = text(c, 30, GOLD, true);
        t.setText("FLIXNET");
        t.setLetterSpacing(0.12f);
        t.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        return t;
    }

    // "1:02:03" or "4:05".
    static String time(long ms) {
        long s = ms / 1000, h = s / 3600, m = s / 60 % 60;
        return h > 0 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, s % 60)
                : String.format(java.util.Locale.ROOT, "%d:%02d", m, s % 60);
    }
}
