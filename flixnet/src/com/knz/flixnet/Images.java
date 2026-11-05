package com.knz.flixnet;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Posters and backgrounds, read off the main thread at about the size they're shown (a TV box has
// little memory, and a background straight from a camera can be 6000 pixels wide), and kept a while.
final class Images {
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 6)) {
        @Override
        protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    interface Done { void loaded(Bitmap b); }

    // Into the view, unless the view has been given another picture meanwhile. Null clears it.
    static void into(ImageView v, File f, int w, int h) {
        String key = f == null ? null : f.getAbsolutePath() + "@" + w;
        v.setTag(key);
        if (key == null) { v.setImageDrawable(null); return; }
        Bitmap hit = CACHE.get(key);
        if (hit != null) { v.setImageBitmap(hit); return; }
        v.setImageDrawable(null);
        load(f, w, h, b -> {
            if (key.equals(v.getTag()) && b != null) {
                v.setAlpha(0f);
                v.setImageBitmap(b);
                v.animate().alpha(1f).setDuration(220).start();
            }
        });
    }

    static void load(File f, int w, int h, Done done) {
        String key = f.getAbsolutePath() + "@" + w;
        Bitmap hit = CACHE.get(key);
        if (hit != null) { done.loaded(hit); return; }
        POOL.execute(() -> {
            Bitmap b = decode(f, w, h);
            if (b != null) CACHE.put(key, b);
            MAIN.post(() -> done.loaded(b));
        });
    }

    private static Bitmap decode(File f, int w, int h) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (o.outWidth <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565; // half the memory; posters don't need the rest
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        } catch (Throwable t) { // a broken file, or out of memory: just no picture
            return null;
        }
    }
}
