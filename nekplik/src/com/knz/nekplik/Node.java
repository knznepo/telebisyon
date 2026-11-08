package com.knz.nekplik;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// A file or folder in the library, wherever it is: on a USB drive or the TV (Local), or on a computer
// sharing its movies with share.py (Remote). The library, the pictures and the player only use this.
//
// key() never changes for the same file (a path, or share://<computer>/<path>), so where it was stopped
// is remembered even when the computer's address changes. uri() is what plays: a path, or a link.
abstract class Node {
    abstract String name();

    abstract boolean isDirectory();

    boolean isFile() { return !isDirectory(); }

    abstract long length();

    abstract long lastModified();

    abstract List<Node> list();

    abstract Node parent();

    abstract String key();

    abstract String uri();

    abstract InputStream open() throws Exception;

    boolean remote() { return false; }

    Node child(String name) {
        for (Node n : list()) if (n.name().equals(name)) return n;
        return null;
    }

    // A small file whole (info.json, an .srt, a picture), up to max bytes.
    byte[] bytes(int max) throws Exception {
        try (InputStream in = open()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                if (out.size() > max) throw new Exception("too big");
            }
            return out.toByteArray();
        }
    }

    @Override
    public boolean equals(Object o) { return o instanceof Node && ((Node) o).key().equals(key()); }

    @Override
    public int hashCode() { return key().hashCode(); }

    // On a drive plugged into the TV, or the TV's own storage.
    static final class Local extends Node {
        final File file;

        Local(File f) { file = f; }

        String name() { return file.getName(); }

        boolean isDirectory() { return file.isDirectory(); }

        long length() { return file.length(); }

        long lastModified() { return file.lastModified(); }

        List<Node> list() {
            List<Node> out = new ArrayList<>();
            File[] all = file.listFiles();
            if (all != null) for (File f : all) out.add(new Local(f));
            return out;
        }

        Node parent() { return file.getParentFile() == null ? null : new Local(file.getParentFile()); }

        @Override
        Node child(String name) {
            File f = new File(file, name);
            return f.exists() ? new Local(f) : null;
        }

        String key() { return file.getAbsolutePath(); }

        String uri() { return file.getAbsolutePath(); }

        InputStream open() throws Exception { return new FileInputStream(file); }
    }

    // On a computer running share.py: what its /tree said, and its /file link for the rest. Small text
    // files (info.json, .srt) came with the tree, so reading the library is a single request.
    static final class Remote extends Node {
        final Shares.Share share;
        final String path; // under the share, "" for its top
        final boolean dir;
        final long size, modified;
        final Remote up;
        byte[] text;
        final Map<String, Remote> children = new LinkedHashMap<>();

        Remote(Shares.Share share, String path, boolean dir, long size, long modified, Remote up) {
            this.share = share;
            this.path = path;
            this.dir = dir;
            this.size = size;
            this.modified = modified;
            this.up = up;
        }

        String name() { return path.substring(path.lastIndexOf('/') + 1); }

        boolean isDirectory() { return dir; }

        long length() { return size; }

        long lastModified() { return modified; }

        List<Node> list() { return new ArrayList<>(children.values()); }

        Node parent() { return up; }

        @Override
        Node child(String name) { return children.get(name); }

        @Override
        boolean remote() { return true; }

        String key() { return "share://" + share.name + "/" + path; }

        String uri() { return share.link("file/" + path); }

        // A picture made small by the phone sharing it (NEKPLIK Share for Android has /img): a few dozen
        // kilobytes instead of the whole file. Null when it can't, and then share.py's full picture.
        byte[] small(int w) {
            if (share.noSmall) return null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(share.link("img/" + path) + "&w=" + w).openConnection();
                c.setConnectTimeout(4000);
                c.setReadTimeout(15000);
                String type = c.getContentType();
                // share.py answers a path it doesn't know with an HTML error page; the phone, plain text.
                if (c.getResponseCode() == 404 && type != null && type.startsWith("text/html")) { share.noSmall = true; return null; }
                if (c.getResponseCode() != 200) return null;
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                    return out.toByteArray();
                }
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        byte[] bytes(int max) throws Exception {
            if (text != null) return text;
            return super.bytes(max);
        }

        InputStream open() throws Exception {
            if (text != null) return new java.io.ByteArrayInputStream(text);
            HttpURLConnection c = (HttpURLConnection) new URL(uri()).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(15000);
            return c.getInputStream();
        }
    }
}
