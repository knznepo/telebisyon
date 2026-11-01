package com.knz.telebisyon;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

// Opt-in (messages.sh turns it on): shows a message over whatever is on the TV, sent from any
// device on the same network. It speaks PiPup's API, so what already sends to PiPup (Home Assistant,
// scripts) works unchanged:
//   curl -X POST http://<TV_IP>:7979/notify -H "Content-Type: application/json" \
//        -d '{"title":"Door","message":"Someone is at the front door","duration":10}'
// Fields (all optional): title, message, duration (seconds, default 30), position (0 top right,
// 1 top left, 2 bottom right, 3 bottom left, 4 centre), titleColor, titleSize, messageColor,
// messageSize, backgroundColor, and media.image.uri / media.image.width for a picture from a URL.
// A form post (title=...&message=...) works too. DELETE /cancel takes the message away early.
// Not part of PiPup: "sound": true plays a short chime with it, "box": false leaves out the card
// behind it (a transparent PNG floats on the picture), and "imageSize" sets the picture's width
// in percent of the screen (5 to 100).
// In a browser (a phone, say): http://<TV_IP>:7979/ is a copy of the home screen whose tiles open on the
// TV (res/raw/home.html), /message a form that sends a message (res/raw/form.html), and /wallpapers
// a page that manages the wallpaper pictures (res/raw/wallpapers.html), and /files the TV's storage
// (Files.java, off until files.sh). pin.sh can put all of it behind a PIN (see signedIn).
//
// Runs only while the "display over other apps" permission is on, which only adb can give here,
// so a TV that never ran messages.sh never listens on the network.
public class Messages extends Service {
    static final int PORT = 7979;
    private static final int MAX_BODY = 64 * 1024, MAX_IMAGE = 8 * 1024 * 1024, MAX_UPLOAD = 40 * 1024 * 1024;
    private static final String CHANNEL = "messages";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Files files = new Files(this);
    private final Call call = new Call(this, main);
    private ServerSocket server, secureServer;
    private volatile boolean stopping;
    private View shown;
    private final Runnable hide = this::hide;

    // Called whenever the home screen starts, so messages come back after a restart.
    static void start(Context c) {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(c)) return;
        try {
            Intent i = new Intent(c, Messages.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) {}
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Android keeps a service that sits in the background running only if it shows a
        // notification. TVs keep it out of sight.
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Messages", NotificationManager.IMPORTANCE_MIN));
            b = new Notification.Builder(this, CHANNEL);
        } else b = new Notification.Builder(this);
        Notification n = b.setSmallIcon(getApplicationInfo().icon).setContentTitle("Messages")
                .setContentText("Listening on port " + PORT).build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);

        Thread t = new Thread(this::listen, "messages");
        t.setDaemon(true);
        t.start();
        Thread secure = new Thread(this::listenSecure, "messages-https");
        secure.setDaemon(true);
        secure.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int id) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopping = true;
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        try { if (secureServer != null) secureServer.close(); } catch (Exception ignored) {}
        call.end();
        main.post(this::hide);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void listen() {
        try {
            server = new ServerSocket(PORT);
            while (!server.isClosed()) {
                final Socket s = server.accept();
                new Thread(() -> answer(s, false), "message").start();
            }
        } catch (Exception ignored) {} // closed, or the port is taken (PiPup itself, say)
    }

    // The same pages on https (Call.SECURE_PORT), for calls: browsers only give a page the camera there.
    // If it can't start (the port still held, say), it tries again every few seconds for a minute.
    private void listenSecure() {
        for (int tries = 0; tries < 12 && !stopping; tries++) {
            try {
                secureServer = Call.secureServer(this);
                while (!secureServer.isClosed()) {
                    final Socket s = secureServer.accept();
                    new Thread(() -> answer(s, true), "message").start();
                }
            } catch (Exception e) {
                if (stopping) return;
                android.util.Log.w("TelebisyonCall", "https on " + Call.SECURE_PORT + ": " + e);
                try { Thread.sleep(5000); } catch (InterruptedException x) { return; }
            }
        }
    }

    // A tiny HTTP server: one request per connection, then close.
    private void answer(Socket s, boolean secure) {
        try {
            s.setSoTimeout(10000);
            InputStream in = s.getInputStream();
            String head = readHead(in);
            String[] lines = head.split("\r\n");
            String[] first = lines[0].split(" ");
            String method = first[0], path = first.length > 1 ? first[1] : "/", query = "";
            int q = path.indexOf('?');
            if (q >= 0) {
                query = path.substring(q + 1);
                path = path.substring(0, q);
            }
            if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
            long length = 0;
            String type = "", cookie = "", pin = "", range = null, origin = null, host = "";
            for (String l : lines) {
                int colon = l.indexOf(':');
                if (colon < 0) continue;
                String name = l.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT), value = l.substring(colon + 1).trim();
                if (name.equals("content-length")) length = Long.parseLong(value);
                else if (name.equals("content-type")) type = value.toLowerCase(java.util.Locale.ROOT);
                else if (name.equals("cookie")) cookie = value;
                else if (name.equals("x-pin")) pin = value;
                else if (name.equals("range")) range = value;
                else if (name.equals("origin")) origin = value;
                else if (name.equals("host")) host = value;
            }
            // Another website open in a browser here can't make changes on the TV: browsers say where
            // a request comes from, and it must be one of these pages. (Scripts don't say.)
            if (!method.equals("GET") && origin != null && !origin.equals((secure ? "https://" : "http://") + host)) {
                reply(s, 403, "{\"error\":\"not from this TV's pages\"}");
                return;
            }
            // The call page the TV shows itself comes in with the call's token instead of the PIN.
            boolean tv = path.startsWith("/call/tv") && call.mine(s, form(query).optString("t"));
            if (!tv && !signedIn(method, path, cookie, pin.isEmpty() ? form(query).optString("pin") : pin)) {
                if (method.equals("GET") && PAGES.contains(path)) reply(s, 200, "text/html; charset=utf-8", page("login"));
                else reply(s, 401, "{\"error\":\"PIN needed\"}");
                return;
            }
            // Pictures go straight to a file, so they can be far bigger than any other request.
            if (method.equals("POST") && path.equals("/avatar")) {
                avatar(s, in, method, length);
                return;
            }
            if (method.equals("POST") && path.equals("/wallpapers/upload")) {
                upload(s, in, form(query).optString("name"), length);
                return;
            }
            if (method.equals("POST") && path.equals("/files/upload")) {
                files.upload(s, in, form(query), length);
                return;
            }
            if (length < 0 || length > MAX_BODY) {
                reply(s, 413, "{\"error\":\"too big\"}");
                return;
            }
            byte[] body = new byte[(int) length];
            for (int got = 0; got < length; ) {
                int n = in.read(body, got, (int) length - got);
                if (n < 0) break;
                got += n;
            }
            String text = new String(body, StandardCharsets.UTF_8);
            if (method.equals("POST") && path.equals("/login")) {
                login(s, params(query, text).optString("pin"));
            } else if (path.equals("/cancel")) {
                main.post(this::hide);
                reply(s, 200, "{\"ok\":true}");
            } else if (method.equals("GET") && path.equals("/")) {
                reply(s, 200, "text/html; charset=utf-8", page("home"));
            } else if (method.equals("GET") && path.equals("/style.css")) {
                reply(s, 200, "text/css; charset=utf-8", page("style"));
            } else if (method.equals("GET") && path.equals("/common.js")) {
                reply(s, 200, "application/javascript; charset=utf-8", page("common"));
            } else if (method.equals("GET") && path.equals("/home.json")) {
                reply(s, 200, homeNow().toString());
            } else if (method.equals("GET") && path.equals("/tile")) {
                tileImage(s, form(query).optString("key"));
            } else if (method.equals("GET") && path.equals("/wallpaper/now")) {
                wallpaperNow(s);
            } else if (path.equals("/avatar") && (method.equals("POST") || method.equals("DELETE"))) {
                avatar(s, in, method, length);
            } else if (method.equals("POST") && path.equals("/tap")) {
                tap(s, params(query, text).optString("key"));
            } else if (method.equals("GET") && (path.equals("/message") || path.equals("/notify"))) {
                reply(s, 200, "text/html; charset=utf-8", page("form"));
            } else if (method.equals("POST") && path.equals("/notify")) {
                JSONObject j = type.startsWith("application/x-www-form-urlencoded") ? form(text) : new JSONObject(text);
                show(j);
                reply(s, 200, "{\"ok\":true}");
            } else if (method.equals("GET") && path.equals("/wallpapers")) {
                reply(s, 200, "text/html; charset=utf-8", page("wallpapers"));
            } else if (method.equals("GET") && path.equals("/wallpapers/list")) {
                reply(s, 200, wallpaperList().toString());
            } else if (method.equals("GET") && path.equals("/wallpapers/thumb")) {
                thumb(s, form(query).optString("name"));
            } else if (method.equals("DELETE") && path.equals("/wallpapers/file")) {
                deletePicture(s, form(query).optString("name"));
            } else if (method.equals("POST") && path.equals("/wallpapers/choose")) {
                choose(s, new JSONObject(text));
            } else if (method.equals("GET") && path.equals("/files")) {
                reply(s, 200, "text/html; charset=utf-8", page("files"));
            } else if (path.startsWith("/files/")) {
                JSONObject j = params(query, text);
                if (range != null) j.put("range", range);
                files.handle(s, method, path, j);
            } else if (path.equals("/call/tv") || path.startsWith("/call/tv/")) {
                callTv(s, method, path, tv, form(query).optString("t"), text);
            } else if (method.equals("GET") && path.equals("/call")) {
                reply(s, 200, "text/html; charset=utf-8", page("call"));
            } else if (method.equals("POST") && path.equals("/call/offer")) {
                if (!secure) reply(s, 400, "{\"error\":\"Calls need the https page\"}");
                else reply(s, 200, call.start(new JSONObject(text)).toString());
            } else if (method.equals("GET") && path.equals("/call/answer")) {
                reply(s, 200, call.answerFor(form(query).optString("id")).toString());
            } else if (method.equals("POST") && path.equals("/call/layout")) {
                JSONObject j = params(query, text);
                call.layout(j.optString("id"), j.optString("size"), j.optBoolean("video", true));
                reply(s, 200, "{\"ok\":true}");
            } else if (method.equals("POST") && path.equals("/call/end")) {
                call.end(params(query, text).optString("id"));
                reply(s, 200, "{\"ok\":true}");
            } else if (CONTROL.contains(path)) {
                control(s, method, path, params(query, text));
            } else reply(s, 404, "{\"error\":\"POST /notify or DELETE /cancel, or open / in a browser\"}");
        } catch (Exception e) {
            try { reply(s, 400, "{\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}"); } catch (Exception ignored) {}
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    // The page the TV shows during a call (calltv.html), and what it asks for: the phone's offer, its
    // answer, the video's shape, and the end of the call when the phone goes away.
    private void callTv(Socket s, String method, String path, boolean tv, String t, String text) throws Exception {
        if (!tv) reply(s, 403, "{\"error\":\"not this call\"}");
        else if (method.equals("GET") && path.equals("/call/tv")) reply(s, 200, "text/html; charset=utf-8", page("calltv"));
        else if (method.equals("GET") && path.equals("/call/tv/offer")) reply(s, 200, call.offer().toString());
        else if (method.equals("POST") && path.equals("/call/tv/answer")) {
            call.answered(new JSONObject(text).getString("sdp"));
            reply(s, 200, "{\"ok\":true}");
        } else if (method.equals("POST") && path.equals("/call/tv/size")) {
            JSONObject j = new JSONObject(text);
            call.videoSize(j.optInt("w"), j.optInt("h"));
            reply(s, 200, "{\"ok\":true}");
        } else if (method.equals("POST") && path.equals("/call/tv/heard")) {
            JSONObject j = new JSONObject(text);
            call.heard(j.optBoolean("sound"), j.optBoolean("playing"));
            reply(s, 200, "{\"ok\":true}");
        } else if (method.equals("POST") && path.equals("/call/tv/end")) {
            call.end(t);
            reply(s, 200, "{\"ok\":true}");
        } else reply(s, 404, "{\"error\":\"no such page\"}");
    }

    // The /wallpapers page: the pictures in MainActivity's wallpapers folder, put there, taken away and
    // picked from a browser. The home screen follows the saved choice as soon as it changes.

    // A picture's file in the folder, or null for a name that isn't a picture there (or tries to
    // reach outside it).
    private File picture(String name) {
        File dir = MainActivity.wallpaperDir(this);
        if (dir == null || name.isEmpty() || name.startsWith(".") || !name.equals(new File(name).getName())) return null;
        File f = new File(dir, name);
        return MainActivity.imageWallpapers(this).contains(f) ? f : null;
    }

    private JSONObject wallpaperList() throws Exception {
        android.content.SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, 0);
        org.json.JSONArray pictures = new org.json.JSONArray(), gradients = new org.json.JSONArray();
        for (File f : MainActivity.imageWallpapers(this)) pictures.put(f.getName());
        org.json.JSONArray colors = new org.json.JSONArray();
        for (int i = 0; i < MainActivity.WALLPAPER_NAMES.length; i++) {
            gradients.put(MainActivity.WALLPAPER_NAMES[i]);
            org.json.JSONArray c = new org.json.JSONArray();
            for (int v : MainActivity.WALLPAPERS[i]) c.put(String.format("#%08X", v));
            colors.put(c);
        }
        return new JSONObject().put("pictures", pictures).put("gradients", gradients).put("gradientColors", colors)
                .put("current", prefs.getString(MainActivity.PREF_WALLPAPER, MainActivity.WALLPAPER_NAMES[0]))
                .put("every", prefs.getInt(MainActivity.PREF_SHUFFLE_EVERY, 0))
                .put("frameAfter", prefs.getInt(MainActivity.PREF_FRAME_AFTER, 5))
                .put("frameEvery", prefs.getInt(MainActivity.PREF_FRAME_EVERY, 60))
                .put("avatar", MainActivity.avatarFile(this).exists())
                .put("account", prefs.contains(MainActivity.PREF_ACCOUNT));
    }

    private void upload(Socket s, InputStream in, String name, long length) throws Exception {
        File dir = MainActivity.wallpaperDir(this);
        // Plain names only: no folders, nothing hidden, and a picture's ending.
        name = new File(name).getName().replaceAll("[^A-Za-z0-9 ._()-]", "_").replaceFirst("^\\.+", "");
        if (dir == null || !name.toLowerCase(java.util.Locale.ROOT).matches(".+\\.(jpe?g|png|webp|bmp|gif)")) {
            reply(s, 400, "{\"error\":\"JPEG, PNG, WebP, BMP or GIF only\"}");
            return;
        }
        if (length <= 0 || length > MAX_UPLOAD) {
            reply(s, 413, "{\"error\":\"pictures up to 40 MB\"}");
            return;
        }
        File part = new File(dir, ".upload-" + System.nanoTime());
        try (OutputStream out = new java.io.FileOutputStream(part)) {
            byte[] buf = new byte[65536];
            for (long got = 0; got < length; ) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, length - got));
                if (n < 0) throw new Exception("upload cut short");
                out.write(buf, 0, n);
                got += n;
            }
        } catch (Exception e) {
            part.delete();
            throw e;
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(part.getPath(), o);
        if (o.outWidth <= 0) {
            part.delete();
            reply(s, 400, "{\"error\":\"not a picture the TV can read\"}");
            return;
        }
        File f = new File(dir, name);
        if (!part.renameTo(f)) {
            part.delete();
            throw new Exception("couldn't save it");
        }
        reply(s, 200, new JSONObject().put("ok", true).put("name", name).toString());
    }

    private void thumb(Socket s, String name) throws Exception {
        File f = picture(name);
        Bitmap b = f == null ? null : MainActivity.decode(f, 320, 180);
        if (b == null) {
            reply(s, 404, "{\"error\":\"no such picture\"}");
            return;
        }
        // Small and cheap to send: the page only needs a tile.
        float scale = Math.min(1f, 480f / Math.max(b.getWidth(), b.getHeight()));
        Bitmap small = scale < 1 ? Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * scale), Math.round(b.getHeight() * scale), true) : b;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        small.compress(Bitmap.CompressFormat.JPEG, 80, out);
        if (small != b) small.recycle();
        b.recycle();
        reply(s, 200, "image/jpeg", out.toByteArray());
    }

    private void deletePicture(Socket s, String name) throws Exception {
        File f = picture(name);
        if (f == null || !f.delete()) {
            reply(s, 404, "{\"error\":\"no such picture\"}");
            return;
        }
        // The one on screen is gone: back to the default.
        android.content.SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, 0);
        String current = prefs.getString(MainActivity.PREF_WALLPAPER, "");
        if (current.equals(MainActivity.IMAGE + name)
                || (current.equals(MainActivity.SHUFFLE) && MainActivity.imageWallpapers(this).size() < 2))
            prefs.edit().remove(MainActivity.PREF_WALLPAPER).apply();
        reply(s, 200, "{\"ok\":true}");
    }

    // {"wallpaper": a gradient's name, "image:<file>" or "shuffle"} and/or {"every": minutes, 0 for
    // only when Home comes back}.
    private void choose(Socket s, JSONObject j) throws Exception {
        android.content.SharedPreferences.Editor e = getSharedPreferences(MainActivity.PREFS, 0).edit();
        if (j.has("wallpaper")) {
            String w = j.getString("wallpaper");
            boolean ok = java.util.Arrays.asList(MainActivity.WALLPAPER_NAMES).contains(w)
                    || (w.equals(MainActivity.SHUFFLE) && MainActivity.imageWallpapers(this).size() >= 2)
                    || (w.startsWith(MainActivity.IMAGE) && picture(w.substring(MainActivity.IMAGE.length())) != null);
            if (!ok) {
                reply(s, 400, "{\"error\":\"no such wallpaper\"}");
                return;
            }
            e.putString(MainActivity.PREF_WALLPAPER, w);
        }
        if (j.has("every")) e.putInt(MainActivity.PREF_SHUFFLE_EVERY, Math.max(0, Math.min(1440, j.getInt("every"))));
        if (j.has("frameAfter")) e.putInt(MainActivity.PREF_FRAME_AFTER, Math.max(0, Math.min(240, j.getInt("frameAfter"))));
        if (j.has("frameEvery")) e.putInt(MainActivity.PREF_FRAME_EVERY, Math.max(10, Math.min(3600, j.getInt("frameEvery"))));
        e.apply();
        reply(s, 200, "{\"ok\":true}");
    }

    // Control, for Home Assistant and the like. Each takes JSON or ?name=value, and answers with /status.
    //   GET  /status                      what's on: the app in front, what's playing, volume, screen
    //   GET  /apps                        the apps that can be opened, with their package names
    //   POST /open   {"app": "netflix"}   a package name, or an app's name (whole, then in part)
    //   POST /home                        back to this home screen
    //   POST /media  {"action": "pause"}  play, pause, playpause, stop, next, previous
    //   POST /volume {"level": 10} | {"change": -2} | {"mute": true}
    // What's playing needs MediaListener on, and the app in front needs usage access; messages.sh
    // gives both. Without them those fields are left out, and /media still works through media keys.
    private static final java.util.List<String> CONTROL =
            java.util.Arrays.asList("/status", "/apps", "/open", "/home", "/media", "/volume");

    static JSONObject params(String query, String body) throws Exception {
        JSONObject j = body.trim().startsWith("{") ? new JSONObject(body) : form(body);
        JSONObject q = form(query);
        for (java.util.Iterator<String> it = q.keys(); it.hasNext(); ) {
            String k = it.next();
            if (!j.has(k)) j.put(k, q.get(k));
        }
        return j;
    }

    private void control(Socket s, String method, String path, JSONObject j) throws Exception {
        android.media.AudioManager audio = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        if (path.equals("/status")) {
            reply(s, 200, status().toString());
            return;
        }
        if (path.equals("/apps")) {
            org.json.JSONArray apps = new org.json.JSONArray();
            for (java.util.Map.Entry<String, String> a : launchable().entrySet())
                apps.put(new JSONObject().put("package", a.getKey()).put("name", a.getValue()));
            reply(s, 200, new JSONObject().put("apps", apps).toString());
            return;
        }
        if (!method.equals("POST")) {
            reply(s, 405, "{\"error\":\"use POST\"}");
            return;
        }
        switch (path) {
            case "/open": {
                String pkg = findApp(j.optString("app"));
                Intent i = pkg == null ? null : launchIntent(pkg);
                if (i == null) {
                    reply(s, 404, "{\"error\":\"no such app; GET /apps lists them\"}");
                    return;
                }
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                break;
            }
            case "/home":
                startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                break;
            case "/media":
                if (!media(j.optString("action").toLowerCase(java.util.Locale.ROOT))) {
                    reply(s, 400, "{\"error\":\"action: play, pause, playpause, stop, next or previous\"}");
                    return;
                }
                break;
            case "/volume": {
                int stream = android.media.AudioManager.STREAM_MUSIC, flags = android.media.AudioManager.FLAG_SHOW_UI;
                if (j.has("mute") && Build.VERSION.SDK_INT >= 23)
                    audio.adjustStreamVolume(stream, j.getBoolean("mute") ? android.media.AudioManager.ADJUST_MUTE
                            : android.media.AudioManager.ADJUST_UNMUTE, flags);
                if (j.has("level"))
                    audio.setStreamVolume(stream, Math.max(0, Math.min(audio.getStreamMaxVolume(stream), j.getInt("level"))), flags);
                if (j.has("change")) {
                    int n = j.getInt("change");
                    for (int k = 0; k < Math.min(Math.abs(n), 100); k++)
                        audio.adjustStreamVolume(stream, n > 0 ? android.media.AudioManager.ADJUST_RAISE
                                : android.media.AudioManager.ADJUST_LOWER, k == 0 ? flags : 0);
                }
                break;
            }
        }
        Thread.sleep(300); // let the change land before saying how things are
        reply(s, 200, status().put("ok", true).toString());
    }

    private JSONObject status() throws Exception {
        JSONObject j = new JSONObject();
        android.os.PowerManager power = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        j.put("screen", power.isInteractive() ? "on" : "off");

        String front = frontApp();
        if (front != null) j.put("app", new JSONObject().put("package", front).put("name", label(front)));

        android.media.AudioManager audio = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        int stream = android.media.AudioManager.STREAM_MUSIC;
        JSONObject v = new JSONObject().put("level", audio.getStreamVolume(stream)).put("max", audio.getStreamMaxVolume(stream));
        if (Build.VERSION.SDK_INT >= 23) v.put("muted", audio.isStreamMute(stream));
        v.put("fixed", audio.isVolumeFixed()); // the TV leaves volume to a soundbar or receiver
        j.put("volume", v);

        android.media.session.MediaController c = playing();
        if (c != null) {
            JSONObject m = new JSONObject().put("package", c.getPackageName()).put("app", label(c.getPackageName()));
            android.media.session.PlaybackState st = c.getPlaybackState();
            if (st != null) {
                String[] names = {"none", "stopped", "paused", "playing", "fast_forwarding", "rewinding", "buffering",
                        "error", "connecting", "skipping_to_previous", "skipping_to_next", "skipping_to_queue_item"};
                m.put("state", st.getState() >= 0 && st.getState() < names.length ? names[st.getState()] : "unknown");
                m.put("position", st.getPosition() / 1000);
            }
            android.media.MediaMetadata md = c.getMetadata();
            if (md != null) {
                String title = md.getString(android.media.MediaMetadata.METADATA_KEY_TITLE);
                String artist = md.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST);
                long duration = md.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION);
                if (title != null) m.put("title", title);
                if (artist != null) m.put("artist", artist);
                if (duration > 0) m.put("duration", duration / 1000);
            }
            j.put("media", m);
        }
        return j;
    }

    // The media session that's playing, else the most recent one; null without MediaListener on.
    private android.media.session.MediaController playing() {
        try {
            android.media.session.MediaSessionManager msm =
                    (android.media.session.MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
            java.util.List<android.media.session.MediaController> all =
                    msm.getActiveSessions(new android.content.ComponentName(this, MediaListener.class));
            for (android.media.session.MediaController c : all) {
                android.media.session.PlaybackState st = c.getPlaybackState();
                if (st != null && st.getState() == android.media.session.PlaybackState.STATE_PLAYING) return c;
            }
            return all.isEmpty() ? null : all.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    // Through the playing app's own controls when it can see them, else as the remote's media keys.
    private boolean media(String action) {
        android.media.session.MediaController c = playing();
        android.media.session.MediaController.TransportControls t = c != null ? c.getTransportControls() : null;
        int key;
        switch (action) {
            case "play": if (t != null) t.play(); key = KeyEvent.KEYCODE_MEDIA_PLAY; break;
            case "pause": if (t != null) t.pause(); key = KeyEvent.KEYCODE_MEDIA_PAUSE; break;
            case "stop": if (t != null) t.stop(); key = KeyEvent.KEYCODE_MEDIA_STOP; break;
            case "next": if (t != null) t.skipToNext(); key = KeyEvent.KEYCODE_MEDIA_NEXT; break;
            case "previous": if (t != null) t.skipToPrevious(); key = KeyEvent.KEYCODE_MEDIA_PREVIOUS; break;
            case "playpause": {
                android.media.session.PlaybackState st = c != null ? c.getPlaybackState() : null;
                if (t != null && st != null) {
                    if (st.getState() == android.media.session.PlaybackState.STATE_PLAYING) t.pause();
                    else t.play();
                }
                key = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
                break;
            }
            default: return false;
        }
        if (t == null) {
            android.media.AudioManager audio = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
            audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
        }
        return true;
    }

    // The app last brought to the front, from usage access; null without it.
    private String frontApp() {
        try {
            android.app.usage.UsageStatsManager usm = (android.app.usage.UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            android.app.usage.UsageEvents events = usm.queryEvents(now - 24 * 3600 * 1000L, now);
            android.app.usage.UsageEvents.Event e = new android.app.usage.UsageEvents.Event();
            String front = null;
            while (events.hasNextEvent()) {
                events.getNextEvent(e);
                if (e.getEventType() == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) front = e.getPackageName();
            }
            return front;
        } catch (Exception e) {
            return null;
        }
    }

    // Apps that can be opened, by package, with their names: TV apps and phone apps alike.
    private java.util.Map<String, String> launchable() {
        java.util.Map<String, String> out = new java.util.TreeMap<>();
        android.content.pm.PackageManager pm = getPackageManager();
        for (String cat : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER})
            for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                String p = ri.activityInfo.packageName;
                if (!p.equals(getPackageName()) && !out.containsKey(p)) out.put(p, String.valueOf(ri.loadLabel(pm)));
            }
        return out;
    }

    private String findApp(String want) {
        if (want.isEmpty()) return null;
        java.util.Map<String, String> apps = launchable();
        if (apps.containsKey(want)) return want;
        String w = want.toLowerCase(java.util.Locale.ROOT);
        for (java.util.Map.Entry<String, String> a : apps.entrySet())
            if (a.getValue().toLowerCase(java.util.Locale.ROOT).equals(w)) return a.getKey();
        for (java.util.Map.Entry<String, String> a : apps.entrySet())
            if (a.getValue().toLowerCase(java.util.Locale.ROOT).contains(w)) return a.getKey();
        return null;
    }

    private Intent launchIntent(String pkg) {
        android.content.pm.PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        return i != null ? i : pm.getLaunchIntentForPackage(pkg);
    }

    private String label(String pkg) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return pkg;
        }
    }

    // The copy of the home screen at / (res/raw/home.html): what MainActivity last laid out, where it
    // put it, and the wallpaper on screen. Clicking a tile there opens it on the TV through /tap.
    private JSONObject homeNow() throws Exception {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        String clock = getSharedPreferences(MainActivity.PREFS, 0).getString("clock", null);
        boolean h24 = clock != null ? clock.equals("24") : android.text.format.DateFormat.is24HourFormat(this);
        java.util.TimeZone tz = java.util.TimeZone.getDefault();
        long now = System.currentTimeMillis();
        JSONObject j = new JSONObject().put("width", dm.widthPixels).put("height", dm.heightPixels)
                .put("pageHeight", Math.max(dm.heightPixels, MainActivity.shownHeight))
                .put("time", now).put("tzOffset", tz.getOffset(now) / 60000).put("h24", h24)
                .put("locale", java.util.Locale.getDefault().toLanguageTag())
                .put("avatarStamp", getSharedPreferences(MainActivity.PREFS, 0).getLong(MainActivity.PREF_AVATAR_STAMP, 0));
        org.json.JSONArray tiles = new org.json.JSONArray();
        for (MainActivity.Shown t : MainActivity.shown) {
            JSONObject o = new JSONObject().put("key", t.key).put("label", t.label)
                    .put("x", t.x).put("y", t.y).put("w", t.w).put("h", t.h)
                    .put("banner", t.banner).put("image", t.image != null);
            int[] icon = t.ops;
            if (t.icon != null) {
                o.put("round", true);
                android.graphics.drawable.Drawable d = t.icon.getDrawable();
                if (d instanceof android.graphics.drawable.LayerDrawable) {
                    // The network icon: a dim whole fan, with the bars it has on top.
                    android.graphics.drawable.LayerDrawable l = (android.graphics.drawable.LayerDrawable) d;
                    android.graphics.drawable.Drawable under = l.getDrawable(0), over = l.getDrawable(l.getNumberOfLayers() - 1);
                    if (under instanceof LineIcon) {
                        org.json.JSONArray dim = new org.json.JSONArray();
                        for (int op : ((LineIcon) under).ops()) dim.put(op);
                        o.put("dimOps", dim);
                    }
                    d = over;
                }
                if (d instanceof LineIcon) icon = ((LineIcon) d).ops();
            }
            if (icon != null) {
                org.json.JSONArray ops = new org.json.JSONArray();
                for (int op : icon) ops.put(op);
                o.put("ops", ops);
            }
            tiles.put(o);
        }
        j.put("tiles", tiles);
        String pic = MainActivity.shownPictureNow;
        if (pic != null && picture(pic) != null) j.put("picture", pic);
        else {
            org.json.JSONArray g = new org.json.JSONArray();
            for (int c : MainActivity.WALLPAPERS[MainActivity.shownGradient]) g.put(String.format("#%08X", c));
            j.put("gradient", g);
        }
        return j;
    }

    // The profile picture by the network icon: POST a picture to set it, DELETE to go back to the
    // account's letter. Kept small and square; the home screen rebuilds its icons to show it.
    private void avatar(Socket s, InputStream in, String method, long length) throws Exception {
        File f = MainActivity.avatarFile(this);
        if (method.equals("DELETE")) {
            f.delete();
        } else {
            if (length <= 0 || length > 15 * 1024 * 1024) {
                reply(s, 413, "{\"error\":\"pictures up to 15 MB\"}");
                return;
            }
            byte[] data = new byte[(int) length];
            for (int got = 0; got < length; ) {
                int n = in.read(data, got, (int) length - got);
                if (n < 0) throw new Exception("upload cut short");
                got += n;
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0) {
                reply(s, 400, "{\"error\":\"not a picture the TV can read\"}");
                return;
            }
            int sample = 1;
            while (Math.min(o.outWidth, o.outHeight) / (sample * 2) >= 256) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int side = Math.min(b.getWidth(), b.getHeight());
            Bitmap sq = Bitmap.createBitmap(b, (b.getWidth() - side) / 2, (b.getHeight() - side) / 2, side, side);
            Bitmap small = Bitmap.createScaledBitmap(sq, 256, 256, true);
            try (OutputStream out = new java.io.FileOutputStream(f)) {
                small.compress(Bitmap.CompressFormat.JPEG, 90, out);
            }
        }
        getSharedPreferences(MainActivity.PREFS, 0).edit()
                .putLong(MainActivity.PREF_AVATAR_STAMP, System.currentTimeMillis()).apply();
        reply(s, 200, "{\"ok\":true}");
    }

    private static MainActivity.Shown shownTile(String key) {
        for (MainActivity.Shown t : MainActivity.shown) if (t.key.equals(key)) return t;
        return null;
    }

    private void tileImage(Socket s, String key) throws Exception {
        MainActivity.Shown t = shownTile(key);
        Bitmap b = t != null ? t.image : null;
        if (b == null || b.isRecycled()) {
            reply(s, 404, "{\"error\":\"no such tile\"}");
            return;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, out);
        reply(s, 200, "image/png", out.toByteArray());
    }

    // The picture on screen, at most the screen's size.
    private void wallpaperNow(Socket s) throws Exception {
        String name = MainActivity.shownPictureNow;
        File f = name != null ? picture(name) : null;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        Bitmap b = f != null ? MainActivity.decode(f, dm.widthPixels, dm.heightPixels) : null;
        if (b == null) {
            reply(s, 404, "{\"error\":\"no picture on screen\"}");
            return;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 85, out);
        b.recycle();
        reply(s, 200, "image/jpeg", out.toByteArray());
    }

    private void tap(Socket s, String key) throws Exception {
        if (shownTile(key) == null) {
            reply(s, 404, "{\"error\":\"no such tile\"}");
            return;
        }
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_OPEN, key).putExtra(MainActivity.EXTRA_OPEN_AT, System.currentTimeMillis()));
        reply(s, 200, "{\"ok\":true}");
    }

    // An optional PIN for everything here (pin.sh sets it): a browser signs in once and keeps a
    // cookie; scripts and Home Assistant send it as an X-Pin header (or ?pin=). Messages themselves
    // (POST /notify, /cancel) never need it, so what speaks PiPup keeps working.
    static final String PREF_PIN_HASH = "pinhash", PREF_PIN_SALT = "pinsalt", PREF_PIN_TOKEN = "pintoken";
    private static final String COOKIE = "telebisyon";
    private static final java.util.List<String> PAGES = java.util.Arrays.asList("/", "/message", "/notify", "/wallpapers", "/files", "/call");
    private static int failures;
    private static long lockedUntil;

    // --es pin 1234, from pin.sh; "off" takes it away. A new PIN signs every browser out.
    static void savePin(android.content.SharedPreferences.Editor e, String pin) {
        pin = pin.trim();
        if (pin.isEmpty() || pin.equalsIgnoreCase("off")) {
            e.remove(PREF_PIN_HASH).remove(PREF_PIN_SALT).remove(PREF_PIN_TOKEN);
            return;
        }
        String salt = random();
        e.putString(PREF_PIN_SALT, salt).putString(PREF_PIN_HASH, hash(salt + pin)).putString(PREF_PIN_TOKEN, random());
    }

    private static String random() {
        byte[] b = new byte[16];
        new java.security.SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static String hash(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean same(String a, String b) {
        return a != null && b != null && java.security.MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private boolean pinIs(String pin) {
        android.content.SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, 0);
        return !pin.isEmpty() && same(prefs.getString(PREF_PIN_HASH, null), hash(prefs.getString(PREF_PIN_SALT, "") + pin));
    }

    private boolean signedIn(String method, String path, String cookie, String pin) {
        android.content.SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, 0);
        String token = prefs.getString(PREF_PIN_TOKEN, null);
        if (token == null) return true; // no PIN set
        if (path.equals("/login") || path.equals("/style.css") || path.equals("/cancel")
                || (method.equals("POST") && path.equals("/notify"))) return true;
        for (String c : cookie.split(";")) {
            String[] kv = c.trim().split("=", 2);
            if (kv.length == 2 && kv[0].equals(COOKIE) && same(kv[1], token)) return true;
        }
        return !pin.isEmpty() && allowedTry() && pinIs(pin);
    }

    // After 5 wrong PINs, none are tried for a minute, so guessing a 4-digit PIN would take days.
    private static synchronized boolean allowedTry() { return System.currentTimeMillis() >= lockedUntil; }

    private static synchronized void wrong() {
        if (++failures >= 5) {
            failures = 0;
            lockedUntil = System.currentTimeMillis() + 60000;
        }
    }

    private void login(Socket s, String pin) throws Exception {
        if (!allowedTry()) {
            reply(s, 429, "{\"error\":\"Too many tries. Wait a minute\"}");
            return;
        }
        if (getSharedPreferences(MainActivity.PREFS, 0).getString(PREF_PIN_TOKEN, null) == null) {
            reply(s, 200, "{\"ok\":true}");
            return;
        }
        if (!pinIs(pin)) {
            wrong();
            Thread.sleep(1000);
            reply(s, 401, "{\"error\":\"Wrong PIN\"}");
            return;
        }
        synchronized (Messages.class) { failures = 0; }
        String token = getSharedPreferences(MainActivity.PREFS, 0).getString(PREF_PIN_TOKEN, "");
        reply(s, 200, "application/json", "{\"ok\":true}".getBytes(StandardCharsets.UTF_8),
                "Set-Cookie: " + COOKIE + "=" + token + "; Path=/; Max-Age=31536000; HttpOnly; SameSite=Lax\r\n");
    }

    // A page from res/raw, read once.
    private final java.util.Map<String, String> pages = new java.util.HashMap<>();

    private synchronized String page(String name) {
        String p = pages.get(name);
        if (p != null) return p;
        try (InputStream in = getResources().openRawResource(getResources().getIdentifier(name, "raw", getPackageName()))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int got; (got = in.read(buf)) > 0; ) out.write(buf, 0, got);
            p = out.toString("UTF-8");
        } catch (Exception e) {
            p = "";
        }
        pages.put(name, p);
        return p;
    }

    private static String readHead(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int b, end = 0;
        while ((b = in.read()) >= 0) {
            out.write(b);
            end = (b == '\r' && (end == 0 || end == 2)) || (b == '\n' && (end == 1 || end == 3)) ? end + 1 : 0;
            if (end == 4) break;
            if (out.size() > 8192) throw new Exception("header too long");
        }
        return out.toString("UTF-8");
    }

    private static JSONObject form(String text) throws Exception {
        JSONObject j = new JSONObject();
        for (String pair : text.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) j.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"), URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
        }
        return j;
    }

    static void reply(Socket s, int code, String json) throws Exception {
        reply(s, code, "application/json", json);
    }

    static void reply(Socket s, int code, String type, String text) throws Exception {
        reply(s, code, type, text.getBytes(StandardCharsets.UTF_8));
    }

    static void reply(Socket s, int code, String type, byte[] body) throws Exception {
        reply(s, code, type, body, "");
    }

    // extra: more header lines, each ending in \r\n.
    static void reply(Socket s, int code, String type, byte[] body, String extra) throws Exception {
        OutputStream out = s.getOutputStream();
        out.write(("HTTP/1.1 " + code + " " + (code == 200 ? "OK" : "Error") + "\r\nContent-Type: " + type + "\r\n"
                + extra + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    // Reads the picture here, off the main thread, then shows the message on it.
    private void show(JSONObject j) throws Exception {
        Bitmap image = null;
        JSONObject media = j.optJSONObject("media");
        JSONObject img = media != null ? media.optJSONObject("image") : null;
        if (img != null && img.optString("uri").startsWith("http")) image = download(img.getString("uri"));
        final Bitmap picture = image;
        main.post(() -> display(j, picture));
    }

    private static Bitmap download(String uri) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(uri).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(10000);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                if (out.size() > MAX_IMAGE) return null;
            }
            byte[] data = out.toByteArray();
            // Read small: a message never needs more than about a quarter of the screen.
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= 1920) sample *= 2; // up to full screen, with imageSize
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // A card in the home screen's look, over everything. It never takes focus or keys, so the remote
    // keeps working the app underneath. A new message replaces the one shown.
    private void display(JSONObject j, Bitmap picture) {
        hide();
        int screenW = getResources().getDisplayMetrics().widthPixels, screenH = getResources().getDisplayMetrics().heightPixels;
        float scale = screenW / 1920f;
        int margin = Math.round(64 * scale);
        // Without the box there's no card: no background, border or padding, and the text gets a shadow.
        boolean box = j.optBoolean("box", true);
        int pad = box ? Math.round(36 * scale) : 0;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(pad, box ? Math.round(28 * scale) : 0, pad, box ? Math.round(30 * scale) : 0);
        if (box) {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(26 * scale);
            bg.setColor(color(j.optString("backgroundColor"), 0xF2231D1F));
            bg.setStroke(Math.max(1, Math.round(2 * scale)), 0x17FFFFFF);
            card.setBackground(bg);
        }

        String title = j.optString("title"), message = j.optString("message");
        if (!title.isEmpty())
            card.addView(text(title, color(j.optString("titleColor"), Color.WHITE),
                    j.optDouble("titleSize", 20) * 1.6 * scale, Typeface.create("sans-serif-medium", Typeface.NORMAL), box, scale));
        if (!message.isEmpty()) {
            TextView m = text(message, color(j.optString("messageColor"), 0xD9FFFFFF),
                    j.optDouble("messageSize", 14) * 1.8 * scale, Typeface.create("sans-serif", Typeface.NORMAL), box, scale);
            if (!title.isEmpty()) m.setPadding(0, Math.round(8 * scale), 0, 0);
            card.addView(m);
        }
        int width = Math.round(560 * scale);
        if (picture != null) {
            JSONObject img = j.optJSONObject("media").optJSONObject("image");
            int percent = j.optInt("imageSize", 0);
            if (percent > 0) {
                width = Math.min(screenW, screenW * Math.max(5, Math.min(100, percent)) / 100 + 2 * pad);
            } else {
                width = Math.max(width, Math.round(img.optInt("width", 480) * scale * 1.5f));
                width = Math.min(width, screenW / 2);
            }
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(picture);
            iv.setAdjustViewBounds(true);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            // A tall picture shrinks to fit the screen's height, keeping its shape.
            iv.setMaxHeight(screenH - 2 * margin);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            if (!title.isEmpty() || !message.isEmpty()) lp.topMargin = Math.round(20 * scale);
            card.addView(iv, lp);
        }
        if (card.getChildCount() == 0) return;

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(width, -2,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        int[] gravity = {Gravity.TOP | Gravity.END, Gravity.TOP | Gravity.START, Gravity.BOTTOM | Gravity.END,
                Gravity.BOTTOM | Gravity.START, Gravity.CENTER};
        int pos = j.optInt("position", 0);
        p.gravity = gravity[pos >= 0 && pos < gravity.length ? pos : 0];
        p.x = pos == 4 || width > screenW - 2 * margin ? 0 : margin;
        p.y = pos == 4 ? 0 : margin;
        p.windowAnimations = android.R.style.Animation_Toast;
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(card, p);
            shown = card;
        } catch (Exception ignored) { // the permission was taken away
            return;
        }
        main.postDelayed(hide, Math.max(1, j.optInt("duration", 30)) * 1000L);
        if (j.optBoolean("sound")) new Thread(Messages::chime).start();
    }

    // Two soft notes, rising, made here so every TV sounds the same. Plays at the TV's own volume.
    private static void chime() {
        int rate = 44100;
        double[][] notes = {{880, 0, 0.5}, {1318.5, 0.16, 0.9}}; // hertz, start and length in seconds
        short[] pcm = new short[(int) (rate * 1.1)];
        for (double[] n : notes) {
            int start = (int) (n[1] * rate), len = (int) (n[2] * rate);
            for (int i = 0; i < len && start + i < pcm.length; i++) {
                double t = (double) i / rate;
                double env = Math.min(1, t / 0.008) * Math.exp(-t * 6); // quick attack, bell-like fade
                double v = Math.sin(2 * Math.PI * n[0] * t) * env * 0.28 * 32767;
                pcm[start + i] = (short) Math.max(-32768, Math.min(32767, pcm[start + i] + v));
            }
        }
        android.media.AudioTrack track = null;
        try {
            track = new android.media.AudioTrack(
                    new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                    new android.media.AudioFormat.Builder().setSampleRate(rate)
                            .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build(),
                    pcm.length * 2, android.media.AudioTrack.MODE_STATIC, android.media.AudioManager.AUDIO_SESSION_ID_GENERATE);
            track.write(pcm, 0, pcm.length);
            track.play();
            Thread.sleep(1200);
        } catch (Exception ignored) {
        } finally {
            if (track != null) track.release();
        }
    }

    private TextView text(String s, int color, double size, Typeface face, boolean box, float scale) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, (float) size);
        t.setTypeface(face);
        if (!box) t.setShadowLayer(10 * scale, 0, 2 * scale, 0xE6000000); // readable on any picture
        return t;
    }

    private static int color(String s, int fallback) {
        try { return s.isEmpty() ? fallback : Color.parseColor(s); }
        catch (Exception e) { return fallback; }
    }

    private void hide() {
        main.removeCallbacks(hide);
        if (shown == null) return;
        try { ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(shown); } catch (Exception ignored) {}
        shown = null;
    }
}
