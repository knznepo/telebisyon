package com.knz.nekplix.share;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.util.Base64;
import android.webkit.MimeTypeMap;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// share.py, for Android: shares the Movies and Series folders on this phone's or box's USB drives (and
// a Nekplix folder on its own storage) with NEKPLIX on the TVs on the same Wi-Fi.
//
// The same as share.py to the TV: it answers "nekplix-discover" on UDP 8766 with a name, port and code,
// and over HTTP gives /tree (every folder and file, small text files included) and /file/<path> (with
// Range, so the player can skip around). Each drive is its own share, with its own code, so two drives
// that both have a Movies folder show as two on the TV. Read-only, and only those folders.
final class Server {
    static final int HTTP_PORT = 8765, DISCOVER_PORT = 8766;
    private static final List<String> VIDEO = Arrays.asList("mp4", "m4v", "mkv", "webm", "avi", "mov", "ts", "m2ts", "mpg", "mpeg", "wmv", "flv", "3gp");
    private static final List<String> TEXT = Arrays.asList("json", "srt");
    private static final List<String> MOVIES = Arrays.asList("movies", "films"), SERIES = Arrays.asList("series", "tv shows", "shows");

    static final class Drive {
        String name, code;
        Item root;
        Map<String, Item> tops = new LinkedHashMap<>(); // "Movies" -> where it is
    }

    // Drives chosen in Android's picker, which phones need for USB drives: kept as their links.
    static java.util.Set<String> chosen(Context c) {
        return new java.util.TreeSet<>(c.getSharedPreferences("share", Context.MODE_PRIVATE).getStringSet("trees", new java.util.HashSet<>()));
    }

    static void choose(Context c, Uri tree, boolean add) {
        java.util.Set<String> s = chosen(c);
        if (add) s.add(tree.toString()); else s.remove(tree.toString());
        c.getSharedPreferences("share", Context.MODE_PRIVATE).edit().putStringSet("trees", s).apply();
    }

    interface Listener {
        void playing(String what, String tv); // a video started on a TV
        void used(); // the TV asked for something: it's in use
    }

    private final Context context;
    private final Listener listener;
    private final String secret;
    private volatile boolean running;
    private ServerSocket http;
    private DatagramSocket discover;
    int port;

    Server(Context c, Listener l) {
        context = c.getApplicationContext();
        listener = l;
        secret = secret(context);
    }

    // The part of every code that only NEKPLIX is told, kept so it stays the same between runs.
    private static String secret(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences("share", Context.MODE_PRIVATE);
        String s = p.getString("secret", null);
        if (s == null) {
            byte[] b = new byte[18];
            new SecureRandom().nextBytes(b);
            s = Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            p.edit().putString("secret", s).apply();
        }
        return s;
    }

    // This phone's or box's name, as its settings have it: "Galaxy S23", "Mi Box".
    static String deviceName(Context c) {
        String n = null;
        if (Build.VERSION.SDK_INT >= 25) n = Settings.Global.getString(c.getContentResolver(), Settings.Global.DEVICE_NAME);
        if (n == null || n.trim().isEmpty()) n = Settings.Secure.getString(c.getContentResolver(), "bluetooth_name");
        if (n == null || n.trim().isEmpty()) n = Build.MODEL;
        return n.trim();
    }

    // Every USB drive or card with a Movies or Series folder at its top: those the device lets apps
    // read as plain paths, those chosen in the picker, and a Nekplix folder on its own storage. Looked
    // at on each request, so plugging a drive in or out needs nothing.
    private List<Object[]> places() { // label, Item, id
        List<Object[]> places = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 24) {
            StorageManager sm = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
            for (StorageVolume v : sm.getStorageVolumes()) {
                if (!v.isRemovable() || !Environment.MEDIA_MOUNTED.equals(v.getState())) continue;
                File dir = directory(v);
                if (dir == null || !dir.canRead()) continue;
                String id = v.getUuid() == null ? dir.getName() : v.getUuid();
                seen.add(id);
                places.add(new Object[]{v.getDescription(context), new Item.Path(dir), id});
            }
        } else {
            File[] all = new File("/storage").listFiles();
            String own = Environment.getExternalStorageDirectory().getAbsolutePath();
            if (all != null) for (File f : all)
                if (!f.getName().equals("emulated") && !f.getName().equals("self") && !own.startsWith(f.getAbsolutePath()) && f.canRead())
                    places.add(new Object[]{f.getName(), new Item.Path(f), f.getName()});
        }
        for (String t : chosen(context)) {
            Uri tree = Uri.parse(t);
            String doc = DocumentsContract.getTreeDocumentId(tree); // "6AED-4117:" for a whole drive
            String volume = doc.contains(":") ? doc.substring(0, doc.indexOf(':')) : doc;
            if (doc.endsWith(":") && seen.contains(volume)) continue; // already readable as a path
            Item.Doc root = Item.Doc.root(context.getContentResolver(), tree);
            if (root != null) places.add(new Object[]{root.name, root, t});
        }
        File own = new File(Environment.getExternalStorageDirectory(), "Nekplix");
        if (own.isDirectory()) places.add(new Object[]{"Nekplix folder on this device", new Item.Path(own), "own"});
        return places;
    }

    List<Drive> drives() {
        List<Drive> out = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        String device = deviceName(context);
        for (Object[] p : places()) {
            Drive d = new Drive();
            d.root = (Item) p[1];
            d.tops = tops(d.root);
            if (d.tops.isEmpty()) continue;
            d.code = secret + "-" + hash((String) p[2]);
            d.name = device;
            out.add(d);
            labels.add((String) p[0]);
        }
        if (out.size() > 1) for (int i = 0; i < out.size(); i++) out.get(i).name = device + " · " + labels.get(i);
        return out;
    }

    // What every drive found looks like, for this app's screen: "SanDisk USB drive" -> its folders.
    // Drives with nothing to share are in it too, with an empty list.
    Map<String, List<String>> overview() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Object[] p : places()) out.put((String) p[0], new ArrayList<>(tops((Item) p[1]).keySet()));
        return out;
    }

    private static String hash(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(h, 0, 6, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private static File directory(StorageVolume v) {
        if (Build.VERSION.SDK_INT >= 30) return v.getDirectory();
        try { return (File) StorageVolume.class.getMethod("getPathFile").invoke(v); }
        catch (Exception e) {
            try { return new File((String) StorageVolume.class.getMethod("getPath").invoke(v)); }
            catch (Exception x) { return null; }
        }
    }

    // Its Movies and Series folders (or, if it is one, itself), as in share.py.
    static Map<String, Item> tops(Item folder) {
        Map<String, Item> out = new LinkedHashMap<>();
        for (Item f : folder.list()) {
            String l = f.name.toLowerCase(Locale.ROOT);
            if (f.dir && (MOVIES.contains(l) || SERIES.contains(l))) out.put(f.name, f);
        }
        if (!out.isEmpty()) return out;
        String l = folder.name.toLowerCase(Locale.ROOT);
        if (MOVIES.contains(l)) out.put("Movies", folder);
        else if (SERIES.contains(l)) out.put("Series", folder);
        return out;
    }

    // Starts answering. Throws if Wi-Fi ports can't be had.
    void start() throws IOException {
        running = true;
        http = new ServerSocket();
        http.setReuseAddress(true);
        IOException last = null;
        for (int p = HTTP_PORT; p < HTTP_PORT + 20; p++) {
            if (p == DISCOVER_PORT) continue;
            try { http.bind(new InetSocketAddress(p)); port = p; last = null; break; } catch (IOException e) { last = e; }
        }
        if (last != null) throw last;
        discover = new DatagramSocket(null);
        discover.setReuseAddress(true);
        discover.setBroadcast(true);
        discover.bind(new InetSocketAddress(DISCOVER_PORT));
        new Thread(this::accept, "share-http").start();
        new Thread(this::answer, "share-discover").start();
    }

    void stop() {
        running = false;
        try { if (http != null) http.close(); } catch (IOException ignored) {}
        if (discover != null) discover.close();
    }

    // NEKPLIX asks the whole Wi-Fi who's sharing: one answer per drive.
    private void answer() {
        byte[] buf = new byte[256];
        while (running) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                discover.receive(p);
                if (!new String(p.getData(), 0, p.getLength(), StandardCharsets.US_ASCII).trim().equals("nekplix-discover")) continue;
                for (Drive d : drives()) {
                    byte[] reply = new JSONObject().put("name", d.name).put("port", port).put("code", d.code).put("version", 1)
                            .toString().getBytes(StandardCharsets.UTF_8);
                    discover.send(new DatagramPacket(reply, reply.length, p.getSocketAddress()));
                }
            } catch (Exception e) {
                if (!running) return;
            }
        }
    }

    private void accept() {
        while (running) {
            try {
                Socket s = http.accept();
                new Thread(() -> serve(s), "share-request").start();
            } catch (IOException e) {
                if (!running) return;
            }
        }
    }

    private void serve(Socket s) {
        try (Socket sock = s; OutputStream out = sock.getOutputStream()) {
            sock.setSoTimeout(30000);
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.ISO_8859_1));
            String line = in.readLine();
            if (line == null) return;
            String[] req = line.split(" ");
            String range = null;
            for (String h; (h = in.readLine()) != null && !h.isEmpty(); )
                if (h.toLowerCase(Locale.ROOT).startsWith("range:")) range = h.substring(6).trim();
            if (req.length < 2 || !(req[0].equals("GET") || req[0].equals("HEAD"))) { status(out, 405, "Not allowed"); return; }
            String target = req[1], query = "";
            int q = target.indexOf('?');
            if (q >= 0) { query = target.substring(q + 1); target = target.substring(0, q); }
            String code = "";
            for (String kv : query.split("&")) if (kv.startsWith("code=")) code = Uri.decode(kv.substring(5));
            Drive drive = null;
            for (Drive d : drives()) if (MessageDigest.isEqual(d.code.getBytes(StandardCharsets.UTF_8), code.getBytes(StandardCharsets.UTF_8))) drive = d;
            if (drive == null) { status(out, 403, "Wrong code"); return; }
            listener.used();
            String path = Uri.decode(target);
            if (path.equals("/tree")) tree(out, drive);
            else if (path.startsWith("/file/")) file(out, drive, path.substring(6), range, req[0].equals("HEAD"), sock);
            else status(out, 404, "Not found");
        } catch (Exception ignored) {} // the player skipped elsewhere, or stopped
    }

    private static void status(OutputStream out, int code, String text) throws IOException {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 " + code + " " + text + "\r\nContent-Type: text/plain\r\nContent-Length: " + b.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.write(b);
    }

    // Every folder and file under Movies and Series (four levels down at most), with the small text
    // files' contents: the TV reads the whole library in this one request.
    private void tree(OutputStream out, Drive d) throws Exception {
        JSONArray entries = new JSONArray();
        for (Map.Entry<String, Item> t : d.tops.entrySet()) {
            entries.put(new JSONObject().put("path", t.getKey()).put("dir", true));
            walk(t.getValue(), t.getKey(), 0, entries);
        }
        byte[] body = new JSONObject().put("name", d.name).put("entries", entries).toString().getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + body.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
    }

    private static void walk(Item dir, String prefix, int depth, JSONArray entries) throws Exception {
        List<Item> dirs = new ArrayList<>();
        for (Item f : dir.list()) {
            if (f.name.startsWith(".")) continue; // hidden, and the ._ files a Mac leaves on exFAT drives
            if (f.dir) {
                if (depth < 3) dirs.add(f);
                continue;
            }
            JSONObject e = new JSONObject().put("path", prefix + "/" + f.name).put("size", f.size).put("mtime", f.mtime / 1000);
            String ext = f.name.contains(".") ? f.name.substring(f.name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
            if (TEXT.contains(ext) && f.size <= 1024 * 1024) {
                try { e.put("b64", Base64.encodeToString(Item.read(f, 1024 * 1024), Base64.NO_WRAP)); } catch (IOException ignored) {}
            }
            entries.put(e);
        }
        for (Item f : dirs) {
            entries.put(new JSONObject().put("path", prefix + "/" + f.name).put("dir", true).put("mtime", f.mtime / 1000));
            walk(f, prefix + "/" + f.name, depth + 1, entries);
        }
    }

    // The file the TV asked for, found by name from its top folder, and never a hidden one.
    private static Item real(Drive d, String path) {
        String[] parts = path.split("/");
        if (parts.length == 0 || !d.tops.containsKey(parts[0])) return null;
        Item f = d.tops.get(parts[0]);
        for (int i = 1; i < parts.length && f != null; i++) {
            if (parts[i].isEmpty()) continue;
            if (parts[i].startsWith(".")) return null;
            f = f.child(parts[i]);
        }
        return f;
    }

    // A file, or the part of it asked for, so the player can skip around.
    private void file(OutputStream out, Drive d, String path, String range, boolean head, Socket sock) throws IOException {
        Item f = real(d, path);
        if (f == null || f.dir) { status(out, 404, "Not found"); return; }
        long size = f.size, start = 0, end = size - 1;
        boolean partial = range != null && range.startsWith("bytes=");
        if (partial) {
            String r = range.substring(6).split(",")[0];
            int dash = r.indexOf('-');
            try {
                String a = r.substring(0, dash).trim(), b = r.substring(dash + 1).trim();
                if (!a.isEmpty()) { start = Long.parseLong(a); end = b.isEmpty() ? size - 1 : Long.parseLong(b); }
                else start = Math.max(0, size - Long.parseLong(b));
            } catch (Exception e) { status(out, 416, "Bad range"); return; }
            if (start >= size) {
                out.write(("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */" + size + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.ISO_8859_1));
                return;
            }
            end = Math.min(end, size - 1);
        }
        String name = f.name, ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if (type == null) type = ext.equals("mkv") ? "video/x-matroska" : "application/octet-stream";
        if (VIDEO.contains(ext) && start == 0 && !head) listener.playing(path, sock.getInetAddress().getHostAddress());
        StringBuilder h = new StringBuilder(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
        h.append("Content-Type: ").append(type).append("\r\nAccept-Ranges: bytes\r\nContent-Length: ").append(end - start + 1).append("\r\n");
        if (partial) h.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(size).append("\r\n");
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (head) return;
        try (FileInputStream in = f.open(); FileChannel ch = in.getChannel()) {
            ch.position(start);
            ByteBuffer buf = ByteBuffer.allocate(256 * 1024);
            long left = end - start + 1;
            while (left > 0 && running) {
                buf.clear();
                if (left < buf.capacity()) buf.limit((int) left);
                int n = ch.read(buf);
                if (n <= 0) break;
                out.write(buf.array(), 0, n);
                left -= n;
            }
        }
    }
}
