package com.knz.telebisyon;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.util.Base64;
import android.view.Gravity;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

// Calls from a phone's browser: its camera and microphone show on the TV straight away, over whatever
// is on, with nothing to answer. One way only: the TV has no camera to send back. With the camera off
// it's audio only, and the TV shows a small "Phone" badge while someone talks, instead of the video.
//
// Browsers only give a page the camera on https, so the TV also listens on SECURE_PORT with its own
// certificate, made here the first time (the browser warns about it once). The phone's page
// (res/raw/call.html) sends its WebRTC offer to POST /call/offer. The TV shows res/raw/calltv.html in
// a WebView over everything; that page, served on 127.0.0.1 and let in by a one-time token, answers
// it. The video then goes straight from the phone to the TV over the Wi-Fi.
final class Call {
    static final int SECURE_PORT = 7980;

    private final Context context;
    private final Handler main;
    private WebView view;
    private WindowManager.LayoutParams params;
    private Object ducking; // the audio focus held while someone talks: an AudioFocusRequest, or the listener before Android 8
    // The call now: its token, the phone's offer, the TV's answer, and whether it's over.
    private String token, offer, answer;
    private boolean full = true, video = true, ended = true;
    private int videoW, videoH;
    // What the TV's page last said it hears, so the phone can show the sound is getting there.
    private boolean sound, playing;
    private long heardAt;

    Call(Context context, Handler main) {
        this.context = context;
        this.main = main;
    }

    // POST /call/offer {"sdp": ..., "size": "full" or "corner", "video": false for audio only}: a new
    // call replaces the one on now.
    // Not synchronized while it waits: the window is made on the main thread, which needs the lock.
    JSONObject start(JSONObject j) throws Exception {
        if (Build.VERSION.SDK_INT < 23) throw new Exception("Calls need Android 6 or newer on the TV");
        String sdp = j.getString("sdp");
        final String t = random();
        synchronized (this) {
            token = t;
            offer = sdp;
            answer = null;
            ended = false;
            full = !"corner".equals(j.optString("size"));
            video = j.optBoolean("video", true);
            videoW = videoH = 0;
            sound = playing = false;
            heardAt = 0;
        }
        final String[] error = {null};
        final CountDownLatch shown = new CountDownLatch(1);
        main.post(() -> {
            try { show(t); } catch (Throwable e) { error[0] = String.valueOf(e.getMessage()); }
            shown.countDown();
        });
        if (!shown.await(5, TimeUnit.SECONDS)) error[0] = "it took too long";
        if (error[0] != null) {
            end(t);
            throw new Exception("The TV couldn't open the call: " + error[0]);
        }
        return new JSONObject().put("id", t);
    }

    // GET /call/answer?id=: the TV's answer once it's ready.
    synchronized JSONObject answerFor(String id) throws Exception {
        if (!id.equals(token) || ended) return new JSONObject().put("ended", true);
        if (answer == null) return new JSONObject().put("wait", true);
        boolean fresh = System.currentTimeMillis() - heardAt < 6000;
        return new JSONObject().put("sdp", answer).put("sound", fresh && sound).put("playing", fresh && playing);
    }

    // POST /call/layout {"id", "size", "video"}: full screen or a corner, and the camera turned on or
    // off, during the call.
    synchronized void layout(String id, String size, boolean withVideo) {
        if (!id.equals(token) || ended) return;
        full = !"corner".equals(size);
        video = withVideo;
        main.post(this::place);
    }

    synchronized void end(String id) {
        if (id == null || id.equals(token)) end();
    }

    synchronized void end() {
        ended = true;
        offer = answer = null;
        main.post(this::hide);
    }

    // The TV's own page, from the WebView: only from this TV, and only with the call's token.
    synchronized boolean mine(Socket s, String t) {
        return s.getInetAddress().isLoopbackAddress() && t != null && t.equals(token) && !ended;
    }

    synchronized JSONObject offer() throws Exception {
        return new JSONObject().put("sdp", offer).put("size", full ? "full" : "corner").put("video", video);
    }

    synchronized void answered(String sdp) { answer = sdp; }

    synchronized void heard(boolean sound, boolean playing) {
        this.sound = sound;
        this.playing = playing;
        heardAt = System.currentTimeMillis();
    }

    // The video's shape, once it plays, so a corner window fits it.
    synchronized void videoSize(int w, int h) {
        videoW = w;
        videoH = h;
        main.post(this::place);
    }

    private void show(String t) {
        hide();
        WebView w = new WebView(context);
        w.setBackgroundColor(Color.TRANSPARENT);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false); // the sound plays without anyone pressing play
        // The page's console.log goes to the TV's log (adb logcat -s TelebisyonCall), for when a call goes wrong.
        // The page says when someone starts and stops talking, so what's playing is turned down meanwhile.
        w.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void talking(boolean on) { main.post(() -> duck(on)); }
        }, "Telebisyon");
        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                android.util.Log.i("TelebisyonCall", m.message());
                return true;
            }
        });
        // Not FLAG_NOT_TOUCHABLE: Android 12+ makes such a window 80% see-through. A TV has no touchscreen,
        // and FLAG_NOT_FOCUSABLE already leaves the remote working the app underneath.
        params = new WindowManager.LayoutParams(-1, -1,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        view = w;
        place();
        ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).addView(w, params);
        w.loadUrl("http://127.0.0.1:" + Messages.PORT + "/call/tv?t=" + t);
    }

    // Full screen, or the bottom right corner at the video's shape (a third of the screen at most), or
    // just the badge there for audio only.
    private void place() {
        if (view == null) return;
        int sw = context.getResources().getDisplayMetrics().widthPixels, sh = context.getResources().getDisplayMetrics().heightPixels;
        boolean f, v;
        int vw, vh;
        synchronized (this) { f = full; v = video; vw = videoW; vh = videoH; }
        String mode = !v ? "audio" : f ? "full" : "corner";
        if (!v) {
            params.width = Math.round(440 * sw / 1920f);
            params.height = Math.round(104 * sw / 1920f);
            params.gravity = Gravity.BOTTOM | Gravity.END;
            params.x = params.y = Math.round(48 * sw / 1920f);
        } else if (f) {
            params.width = params.height = -1;
            params.x = params.y = 0;
            params.gravity = Gravity.CENTER;
        } else {
            float aspect = vw > 0 && vh > 0 ? (float) vw / vh : 16f / 9;
            int w = sw / 3, h = Math.round(w / aspect);
            if (h > sh * 0.45f) { h = Math.round(sh * 0.45f); w = Math.round(h * aspect); }
            params.width = w;
            params.height = h;
            params.gravity = Gravity.BOTTOM | Gravity.END;
            params.x = params.y = Math.round(48 * sw / 1920f);
        }
        if (view.isAttachedToWindow()) {
            try { ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).updateViewLayout(view, params); }
            catch (Exception ignored) {}
        }
        view.evaluateJavascript("document.body.className='" + mode + "'", null);
    }

    // While someone talks, what's playing (YouTube, Netflix…) is turned down, the way map directions
    // dip music: a short "may duck" focus request for speech. Android 8+ turns the other app down by
    // itself; before that, apps choose (most turn down, a few pause). It comes back when they stop.
    private void duck(boolean on) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (on == (ducking != null) || am == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            if (on) {
                AudioFocusRequest r = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setOnAudioFocusChangeListener(change -> {}).build();
                if (am.requestAudioFocus(r) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) ducking = r;
            } else {
                am.abandonAudioFocusRequest((AudioFocusRequest) ducking);
                ducking = null;
            }
        } else {
            if (on) {
                AudioManager.OnAudioFocusChangeListener l = change -> {};
                if (am.requestAudioFocus(l, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) ducking = l;
            } else {
                am.abandonAudioFocus((AudioManager.OnAudioFocusChangeListener) ducking);
                ducking = null;
            }
        }
    }

    private void hide() {
        duck(false);
        if (view == null) return;
        WebView w = view;
        view = null;
        try { ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).removeView(w); } catch (Exception ignored) {}
        w.destroy();
    }

    private static String random() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    // The https side. The key and certificate are made once and kept; a new one is made only when the
    // TV's address changes or it's a month from running out, since each new one means a new warning.
    private static final String PREF_KEY = "tlskey", PREF_CERT = "tlscert", PREF_NAMES = "tlsnames";

    static ServerSocket secureServer(Context c) throws Exception {
        SharedPreferences prefs = c.getSharedPreferences(MainActivity.PREFS, 0);
        List<byte[]> ips = addresses();
        StringBuilder names = new StringBuilder();
        for (byte[] ip : ips) names.append(InetAddress.getByAddress(ip).getHostAddress()).append(' ');
        PrivateKey key = null;
        X509Certificate cert = null;
        try {
            if (names.toString().equals(prefs.getString(PREF_NAMES, null))) {
                key = KeyFactory.getInstance("EC").generatePrivate(
                        new PKCS8EncodedKeySpec(Base64.decode(prefs.getString(PREF_KEY, ""), Base64.NO_WRAP)));
                cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(
                        new java.io.ByteArrayInputStream(Base64.decode(prefs.getString(PREF_CERT, ""), Base64.NO_WRAP)));
                if (cert.getNotAfter().getTime() - System.currentTimeMillis() < 30L * 86400000) cert = null;
            }
        } catch (Exception e) {
            cert = null;
        }
        if (cert == null) {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair kp = g.generateKeyPair();
            byte[] der = certificate(kp, ips);
            key = kp.getPrivate();
            cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(der));
            prefs.edit().putString(PREF_KEY, Base64.encodeToString(key.getEncoded(), Base64.NO_WRAP))
                    .putString(PREF_CERT, Base64.encodeToString(der, Base64.NO_WRAP))
                    .putString(PREF_NAMES, names.toString()).apply();
        }
        char[] pass = "telebisyon".toCharArray();
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setKeyEntry("tls", key, pass, new Certificate[]{cert});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pass);
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(kmf.getKeyManagers(), null, null);
        // Reused, since right after an update the old copy's connections can still hold the port a while.
        ServerSocket server = ssl.getServerSocketFactory().createServerSocket();
        server.setReuseAddress(true);
        server.bind(new java.net.InetSocketAddress(SECURE_PORT));
        return server;
    }

    // The TV's own IPv4 addresses (Wi-Fi, Ethernet), sorted so the same set always reads the same.
    private static List<byte[]> addresses() {
        List<String> found = new ArrayList<>();
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback()) continue;
                for (InetAddress a : Collections.list(n.getInetAddresses()))
                    if (a instanceof Inet4Address) found.add(a.getHostAddress());
            }
        } catch (Exception ignored) {}
        Collections.sort(found);
        List<byte[]> ips = new ArrayList<>();
        for (String s : found) {
            try { ips.add(InetAddress.getByName(s).getAddress()); } catch (Exception ignored) {}
        }
        return ips;
    }

    // A self-signed certificate, written out in DER by hand, since Android has no public API for
    // making one: "Telebisyon", for the TV's addresses, good for 397 days (browsers' limit).
    private static byte[] certificate(KeyPair kp, List<byte[]> ips) throws Exception {
        byte[] sigAlg = seq(oid("1.2.840.10045.4.3.2")); // ECDSA with SHA-256
        byte[] name = seq(der(0x31, seq(oid("2.5.4.3"), der(0x0C, "Telebisyon".getBytes(StandardCharsets.UTF_8)))));
        long now = System.currentTimeMillis();
        byte[] serial = new byte[16];
        new SecureRandom().nextBytes(serial);
        serial[0] = (byte) ((serial[0] & 0x7F) | 0x01); // positive, and no leading zero
        ByteArrayOutputStream alt = new ByteArrayOutputStream();
        alt.write(der(0x82, "telebisyon.local".getBytes(StandardCharsets.US_ASCII)));
        for (byte[] ip : ips) alt.write(der(0x87, ip));
        byte[] extensions = der(0xA3, seq(
                ext("2.5.29.17", false, der(0x30, alt.toByteArray())),     // the TV's names and addresses
                ext("2.5.29.15", true, der(0x03, new byte[]{7, (byte) 0x80})), // signing only
                ext("2.5.29.37", false, seq(oid("1.3.6.1.5.5.7.3.1"))),   // for a server
                ext("2.5.29.19", false, seq())));                          // not a CA
        byte[] tbs = seq(der(0xA0, der(0x02, new byte[]{2})), der(0x02, serial), sigAlg, name,
                seq(time(now - 86400000L), time(now + 397L * 86400000)), name, kp.getPublic().getEncoded(), extensions);
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(kp.getPrivate());
        s.update(tbs);
        byte[] sig = s.sign();
        byte[] bits = new byte[sig.length + 1];
        System.arraycopy(sig, 0, bits, 1, sig.length);
        return seq(tbs, sigAlg, der(0x03, bits));
    }

    private static byte[] ext(String id, boolean critical, byte[] value) {
        return critical ? seq(oid(id), der(0x01, new byte[]{(byte) 0xFF}), der(0x04, value)) : seq(oid(id), der(0x04, value));
    }

    private static byte[] time(long t) {
        SimpleDateFormat f = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return der(0x17, f.format(new Date(t)).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] oid(String dotted) {
        String[] p = dotted.split("\\.");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(Integer.parseInt(p[0]) * 40 + Integer.parseInt(p[1]));
        for (int i = 2; i < p.length; i++) {
            long v = Long.parseLong(p[i]);
            int shift = 63 - Long.numberOfLeadingZeros(v | 1);
            for (int b = shift / 7; b > 0; b--) out.write((int) (0x80 | (v >> (7 * b)) & 0x7F));
            out.write((int) (v & 0x7F));
        }
        return der(0x06, out.toByteArray());
    }

    private static byte[] seq(byte[]... parts) { return der(0x30, parts); }

    private static byte[] der(int tag, byte[]... parts) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] p : parts) body.write(p, 0, p.length);
        int n = body.size();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        if (n < 0x80) out.write(n);
        else if (n < 0x100) { out.write(0x81); out.write(n); }
        else { out.write(0x82); out.write(n >> 8); out.write(n & 0xFF); }
        out.write(body.toByteArray(), 0, n);
        return out.toByteArray();
    }
}
