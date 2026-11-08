package com.knz.nekplik;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Posters and backgrounds, read off the main thread at about the size they're shown (a TV box has
// little memory, and a background straight from a camera can be 6000 pixels wide), and kept a while.
// From a drive they're read in place; from a phone sharing them, already made small there; from a
// computer (share.py), downloaded first. Sizes go in steps of 100, so screens asking for nearly the same
// size (the rows, search) share what's loaded.
final class Images {
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    // Backgrounds on their own, so the big picture at the top never waits behind a row of posters.
    private static final ExecutorService FIRST = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 6)) {
        @Override
        protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    interface Done { void loaded(Bitmap b); }

    // Into the view, unless the view has been given another picture meanwhile. Null clears it.
    private static int step(int w) { return (w + 99) / 100 * 100; }

    static void into(ImageView v, Node f, int w, int h) {
        String key = f == null ? null : f.key() + "@" + step(w);
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

    static void load(Node f, int w0, int h0, Done done) { load(f, w0, h0, done, false); }

    // A background: ahead of the posters.
    static void loadFirst(Node f, int w0, int h0, Done done) { load(f, w0, h0, done, true); }

    private static void load(Node f, int w0, int h0, Done done, boolean first) {
        int w = step(w0), h = h0 * w / Math.max(1, w0);
        String key = f.key() + "@" + w;
        Bitmap hit = CACHE.get(key);
        if (hit != null) { done.loaded(hit); return; }
        (first ? FIRST : POOL).execute(() -> {
            Bitmap b = decode(f, w, h);
            if (b != null) CACHE.put(key, b);
            MAIN.post(() -> done.loaded(b));
        });
    }

    private static Bitmap decode(Node f, int w, int h) {
        try {
            byte[] data = null;
            if (f instanceof Node.Remote) data = ((Node.Remote) f).small(w);
            if (data == null && f.remote()) data = f.bytes(16 * 1024 * 1024);
            String path = f.remote() ? null : f.uri();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            if (data != null) BitmapFactory.decodeByteArray(data, 0, data.length, o);
            else BitmapFactory.decodeFile(path, o);
            if (o.outWidth <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565; // half the memory; posters don't need the rest
            return data != null ? BitmapFactory.decodeByteArray(data, 0, data.length, o) : BitmapFactory.decodeFile(path, o);
        } catch (Throwable t) { // a broken file, or out of memory: just no picture
            return null;
        }
    }
}
