package com.knz.telebisyon;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

// The /files page (res/raw/files.html): the TV's storage and USB drives from a browser, like an FTP
// app. Off until files.sh turns it on: that gives "All files access" over adb (the storage
// permissions before Android 11) and sets PREF_FILES. Only paths inside the TV's storage or a USB
// drive are allowed, after following any links.
//   GET  /files/roots                {on, install, roots: [{name, path, free, total}]}
//   GET  /files/list?path=           {path, root, parent, items: [{name, dir, size, time}]}
//   GET  /files/get?path=[&dl=1]     the file (dl: as a download); Range works, so videos can seek
//   GET  /files/thumb?path=          a small JPEG of a picture or video
//   POST /files/upload?dir=&name=    the file as the body; a name that's taken gets " (2)"
//   POST /files/mkdir   {"dir", "name"}
//   POST /files/rename  {"path", "name"}
//   POST /files/delete  {"path"}     a folder goes with everything in it
//   POST /files/install {"path"}     an APK: the TV asks whether to install it, OK on the remote
final class Files {
    static final String PREF_FILES = "files";
    private static final String APK = "application/vnd.android.package-archive";

    private final Context c;

    Files(Context c) { this.c = c; }

    // Turned on with files.sh, and the permission is still there.
    static boolean on(Context c) {
        if (!c.getSharedPreferences(MainActivity.PREFS, 0).getBoolean(PREF_FILES, false)) return false;
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        if (Build.VERSION.SDK_INT >= 23)
            return c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        return true;
    }

    // --es files on|off, from files.sh.
    static void save(SharedPreferences.Editor e, String value) {
        if (value.trim().equalsIgnoreCase("on")) e.putBoolean(PREF_FILES, true);
        else e.remove(PREF_FILES);
    }

    void handle(Socket s, String method, String path, JSONObject j) throws Exception {
        if (path.equals("/files/roots")) {
            roots(s);
            return;
        }
        if (!on(c)) {
            Messages.reply(s, 403, "{\"error\":\"Files are off. Turn them on with files.sh\"}");
            return;
        }
        if (method.equals("GET") && path.equals("/files/list")) list(s, j.optString("path"));
        else if (method.equals("GET") && path.equals("/files/get")) send(s, j.optString("path"), j.optString("dl").equals("1"), j.optString("range", null));
        else if (method.equals("GET") && path.equals("/files/thumb")) thumb(s, j.optString("path"));
        else if (method.equals("POST") && path.equals("/files/mkdir")) mkdir(s, j.optString("dir"), j.optString("name"));
        else if (method.equals("POST") && path.equals("/files/rename")) rename(s, j.optString("path"), j.optString("name"));
        else if (method.equals("POST") && path.equals("/files/delete")) delete(s, j.optString("path"));
        else if (method.equals("POST") && path.equals("/files/install")) install(s, j.optString("path"));
        else Messages.reply(s, 404, "{\"error\":\"no such thing\"}");
    }

    // The TV's own storage, then any USB drive or memory card that's in.
    private Map<String, File> roots() {
        Map<String, File> r = new LinkedHashMap<>();
        r.put("TV storage", Environment.getExternalStorageDirectory());
        if (Build.VERSION.SDK_INT >= 24) {
            android.os.storage.StorageManager sm = c.getSystemService(android.os.storage.StorageManager.class);
            for (android.os.storage.StorageVolume v : sm.getStorageVolumes()) {
                if (v.isPrimary() || !Environment.MEDIA_MOUNTED.equals(v.getState())) continue;
                File dir = Build.VERSION.SDK_INT >= 30 ? v.getDirectory()
                        : v.getUuid() != null ? new File("/storage/" + v.getUuid()) : null;
                if (dir == null || !dir.isDirectory()) continue;
                String name = v.getDescription(c);
                for (int i = 2; r.containsKey(name); i++) name = v.getDescription(c) + " " + i;
                r.put(name, dir);
            }
        }
        return r;
    }

    // The root a path is in, with the path made canonical; null for anywhere else.
    private Map.Entry<String, File> rootOf(String path) throws Exception {
        if (path == null || path.isEmpty()) return null;
        File f = new File(path).getCanonicalFile();
        for (Map.Entry<String, File> e : roots().entrySet()) {
            String root = e.getValue().getCanonicalPath();
            if (f.getPath().equals(root) || f.getPath().startsWith(root + "/"))
                return new java.util.AbstractMap.SimpleEntry<>(e.getKey(), f);
        }
        return null;
    }

    private File inside(String path) throws Exception {
        Map.Entry<String, File> e = rootOf(path);
        return e == null ? null : e.getValue();
    }

    // Inside, and not a root itself: what can be renamed or deleted.
    private File item(String path) throws Exception {
        File f = inside(path);
        if (f == null) return null;
        for (File root : roots().values()) if (f.getPath().equals(root.getCanonicalPath())) return null;
        return f;
    }

    // A plain name: no folders, nothing FAT drives can't hold.
    private static String clean(String name) {
        name = new File(name.replace('\\', '/')).getName().replaceAll("[\\x00-\\x1f:*?\"<>|]", "_").trim();
        return name.isEmpty() || name.equals(".") || name.equals("..") || name.length() > 255 ? null : name;
    }

    private void roots(Socket s) throws Exception {
        JSONArray a = new JSONArray();
        boolean on = on(c);
        if (on) for (Map.Entry<String, File> e : roots().entrySet()) {
            File f = e.getValue();
            a.put(new JSONObject().put("name", e.getKey()).put("path", f.getCanonicalPath())
                    .put("free", f.getUsableSpace()).put("total", f.getTotalSpace()));
        }
        boolean install = Build.VERSION.SDK_INT < 26 || c.getPackageManager().canRequestPackageInstalls();
        Messages.reply(s, 200, new JSONObject().put("on", on).put("install", install).put("roots", a).toString());
    }

    private void list(Socket s, String path) throws Exception {
        Map.Entry<String, File> e = rootOf(path);
        File dir = e == null ? null : e.getValue();
        File[] all = dir != null && dir.isDirectory() ? dir.listFiles() : null;
        if (all == null) {
            Messages.reply(s, 404, "{\"error\":\"Can't open this folder\"}");
            return;
        }
        Arrays.sort(all, (x, y) -> x.isDirectory() != y.isDirectory() ? (x.isDirectory() ? -1 : 1)
                : x.getName().compareToIgnoreCase(y.getName()));
        JSONArray items = new JSONArray();
        for (File f : all) {
            JSONObject i = new JSONObject().put("name", f.getName()).put("dir", f.isDirectory()).put("time", f.lastModified());
            if (!f.isDirectory()) i.put("size", f.length());
            items.put(i);
        }
        boolean atRoot = dir.getPath().equals(roots().get(e.getKey()).getCanonicalPath());
        Messages.reply(s, 200, new JSONObject().put("path", dir.getPath()).put("root", e.getKey())
                .put("parent", atRoot ? JSONObject.NULL : dir.getParent()).put("items", items).toString());
    }

    private static String type(String name) {
        int dot = name.lastIndexOf('.');
        String t = dot < 0 ? null : android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT));
        if (t == null) return "application/octet-stream";
        return t.startsWith("text/") ? t + "; charset=utf-8" : t;
    }

    // Streamed, so any size works. A browser asks for parts of a video as it plays and seeks.
    private void send(Socket s, String path, boolean download, String range) throws Exception {
        File f = inside(path);
        if (f == null || !f.isFile()) {
            Messages.reply(s, 404, "{\"error\":\"no such file\"}");
            return;
        }
        long len = f.length(), from = 0, to = len - 1;
        boolean partial = false;
        if (range != null && range.startsWith("bytes=") && !range.contains(",") && len > 0) {
            String[] p = range.substring(6).trim().split("-", 2);
            try {
                if (p[0].isEmpty()) from = Math.max(0, len - Long.parseLong(p[1]));
                else {
                    from = Long.parseLong(p[0]);
                    if (p.length > 1 && !p[1].isEmpty()) to = Math.min(to, Long.parseLong(p[1]));
                }
                partial = from <= to;
            } catch (NumberFormatException ignored) {}
            if (!partial) {
                from = 0;
                to = len - 1;
            }
        }
        String name = java.net.URLEncoder.encode(f.getName(), "UTF-8").replace("+", "%20");
        StringBuilder h = new StringBuilder("HTTP/1.1 ").append(partial ? "206 Partial Content" : "200 OK")
                .append("\r\nContent-Type: ").append(type(f.getName()))
                .append("\r\nContent-Length: ").append(len == 0 ? 0 : to - from + 1)
                .append("\r\nAccept-Ranges: bytes")
                .append("\r\nContent-Disposition: ").append(download ? "attachment" : "inline").append("; filename*=UTF-8''").append(name);
        if (partial) h.append("\r\nContent-Range: bytes ").append(from).append('-').append(to).append('/').append(len);
        h.append("\r\nConnection: close\r\n\r\n");
        OutputStream out = s.getOutputStream();
        out.write(h.toString().getBytes(StandardCharsets.UTF_8));
        try (FileInputStream in = new FileInputStream(f)) {
            in.getChannel().position(from);
            byte[] buf = new byte[65536];
            for (long left = len == 0 ? 0 : to - from + 1; left > 0; ) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) break;
                out.write(buf, 0, n);
                left -= n;
            }
        }
        out.flush();
    }

    private void thumb(Socket s, String path) throws Exception {
        File f = inside(path);
        String t = f != null && f.isFile() ? type(f.getName()) : "";
        Bitmap b = null;
        if (t.startsWith("image/") && f.length() < 50L * 1024 * 1024) b = MainActivity.decode(f, 160, 160);
        else if (t.startsWith("video/"))
            b = android.media.ThumbnailUtils.createVideoThumbnail(f.getPath(), android.provider.MediaStore.Images.Thumbnails.MINI_KIND);
        if (b == null) {
            Messages.reply(s, 404, "{\"error\":\"no picture\"}");
            return;
        }
        float scale = Math.min(1f, 240f / Math.max(b.getWidth(), b.getHeight()));
        Bitmap small = scale < 1 ? Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * scale), Math.round(b.getHeight() * scale), true) : b;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        small.compress(Bitmap.CompressFormat.JPEG, 80, out);
        if (small != b) small.recycle();
        b.recycle();
        Messages.reply(s, 200, "image/jpeg", out.toByteArray());
    }

    // A name that isn't taken yet in dir: "name.ext", then "name (2).ext" and so on.
    private static File free(File dir, String name) {
        File f = new File(dir, name);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; f.exists(); i++) f = new File(dir, base + " (" + i + ")" + ext);
        return f;
    }

    // Other apps (a gallery, a video player) see new and changed files once Android has looked at them.
    private void scan(File... files) {
        String[] paths = new String[files.length];
        for (int i = 0; i < files.length; i++) paths[i] = files[i].getPath();
        android.media.MediaScannerConnection.scanFile(c, paths, null, null);
    }

    void upload(Socket s, InputStream in, JSONObject q, long length) throws Exception {
        if (!on(c)) {
            Messages.reply(s, 403, "{\"error\":\"Files are off. Turn them on with files.sh\"}");
            return;
        }
        File dir = inside(q.optString("dir"));
        String name = clean(q.optString("name"));
        if (dir == null || !dir.isDirectory() || name == null) {
            Messages.reply(s, 400, "{\"error\":\"Can't put a file there\"}");
            return;
        }
        if (length < 0 || length > dir.getUsableSpace()) {
            Messages.reply(s, 507, "{\"error\":\"Not enough space on the TV\"}");
            return;
        }
        // Into a hidden file first, so a cut-off upload never looks like a whole one.
        File part = new File(dir, ".telebisyon-upload-" + System.nanoTime());
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
        File f = free(dir, name);
        if (!part.renameTo(f)) {
            part.delete();
            throw new Exception("couldn't save it");
        }
        scan(f);
        Messages.reply(s, 200, new JSONObject().put("ok", true).put("name", f.getName()).toString());
    }

    private void mkdir(Socket s, String path, String name) throws Exception {
        File dir = inside(path);
        name = clean(name);
        File f = dir != null && name != null ? new File(dir, name) : null;
        if (f == null || f.exists() || !f.mkdir()) {
            Messages.reply(s, 400, "{\"error\":\"" + (f != null && f.exists() ? "That name is taken" : "Can't make a folder there") + "\"}");
            return;
        }
        Messages.reply(s, 200, "{\"ok\":true}");
    }

    private void rename(Socket s, String path, String name) throws Exception {
        File f = item(path);
        name = clean(name);
        File to = f != null && name != null ? new File(f.getParentFile(), name) : null;
        if (to == null || to.exists() || !f.renameTo(to)) {
            Messages.reply(s, 400, "{\"error\":\"" + (to != null && to.exists() ? "That name is taken" : "Can't rename that") + "\"}");
            return;
        }
        scan(f, to);
        Messages.reply(s, 200, "{\"ok\":true}");
    }

    // Folders with everything in them. A link to a folder goes, not what it links to.
    private static boolean remove(File f) throws Exception {
        if (f.isDirectory() && f.getCanonicalPath().equals(f.getAbsolutePath())) {
            File[] in = f.listFiles();
            if (in != null) for (File g : in) remove(g);
        }
        return f.delete();
    }

    private void delete(Socket s, String path) throws Exception {
        File f = item(path);
        if (f == null || !f.exists() || !remove(f)) {
            Messages.reply(s, 400, "{\"error\":\"Can't delete that\"}");
            return;
        }
        scan(f);
        Messages.reply(s, 200, "{\"ok\":true}");
    }

    // Android's own install prompt, on the TV: someone there presses Install. Opening it from here
    // works because the app may show things over other apps (messages.sh).
    private void install(Socket s, String path) throws Exception {
        File f = inside(path);
        if (f == null || !f.isFile() || !f.getName().toLowerCase(Locale.ROOT).endsWith(".apk")) {
            Messages.reply(s, 400, "{\"error\":\"Not an APK\"}");
            return;
        }
        if (Build.VERSION.SDK_INT >= 26 && !c.getPackageManager().canRequestPackageInstalls()) {
            Messages.reply(s, 403, "{\"error\":\"Installing apps isn't allowed. Run files.sh again\"}");
            return;
        }
        Uri uri = Build.VERSION.SDK_INT >= 24 ? ApkProvider.uri(f) : Uri.fromFile(f);
        c.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION));
        Messages.reply(s, 200, "{\"ok\":true}");
    }
}
