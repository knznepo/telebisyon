package com.knz.nekplik;

import android.net.Uri;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// Computers sharing movies with share.py, on the same Wi-Fi. Finding them: a broadcast "nekplik-discover"
// on UDP port 8766, which every share.py answers with its name, port and code. Then each one's whole
// folder list (with its small text files) comes in one request to /tree.
final class Shares {
    static final int PORT = 8766;

    static final class Share {
        String name, host, code;
        int port;
        volatile boolean noSmall; // share.py: no /img, so pictures come full size

        // A link on this computer, the code included: each part of the path encoded.
        String link(String path) {
            StringBuilder p = new StringBuilder();
            for (String part : path.split("/")) {
                if (p.length() > 0) p.append('/');
                p.append(Uri.encode(part));
            }
            return "http://" + host + ":" + port + "/" + p + "?code=" + Uri.encode(code);
        }
    }

    // Asks the Wi-Fi, and listens for answers for a moment. Nothing there: an empty list. Also asks
    // each computer or phone that answered before straight to its address: a phone asleep often doesn't
    // hear broadcasts, though it still shares, and answers when asked directly.
    static List<Share> find(android.content.Context c, int waitMs) {
        android.content.SharedPreferences prefs = c.getSharedPreferences("shares", android.content.Context.MODE_PRIVATE);
        java.util.Set<String> known = new java.util.HashSet<>(prefs.getStringSet("hosts", new java.util.HashSet<>()));
        List<Share> out = find(waitMs, known);
        java.util.Set<String> now = new java.util.LinkedHashSet<>();
        for (Share sh : out) now.add(sh.host);
        for (String h : known) if (now.size() < 8) now.add(h); // kept a while, in case it's off for now
        if (!now.equals(known)) prefs.edit().putStringSet("hosts", now).apply();
        return out;
    }

    private static List<Share> find(int waitMs, java.util.Set<String> known) {
        List<Share> out = new ArrayList<>();
        try (DatagramSocket s = new DatagramSocket()) {
            s.setBroadcast(true);
            s.setSoTimeout(200);
            byte[] ask = "nekplik-discover".getBytes(StandardCharsets.US_ASCII);
            List<InetAddress> to_ = broadcasts();
            for (String h : known) try { to_.add(InetAddress.getByName(h)); } catch (Exception ignored) {}
            for (InetAddress to : to_) {
                try { s.send(new DatagramPacket(ask, ask.length, to, PORT)); } catch (Exception ignored) {}
            }
            long until = System.currentTimeMillis() + waitMs;
            byte[] buf = new byte[2048];
            while (System.currentTimeMillis() < until) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try { s.receive(p); } catch (SocketTimeoutException t) { continue; }
                try {
                    JSONObject j = new JSONObject(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));
                    Share sh = new Share();
                    sh.name = j.getString("name");
                    sh.port = j.getInt("port");
                    sh.code = j.getString("code");
                    sh.host = p.getAddress().getHostAddress();
                    boolean seen = false;
                    for (Share o : out) seen |= o.name.equals(sh.name) && o.host.equals(sh.host);
                    if (!seen) out.add(sh);
                } catch (Exception ignored) {} // not share.py
            }
        } catch (Exception ignored) {}
        return out;
    }

    // Everyone on the network: 255.255.255.255, and each network's own broadcast address, since some
    // routers only pass on one of them.
    private static List<InetAddress> broadcasts() {
        List<InetAddress> out = new ArrayList<>();
        try { out.add(InetAddress.getByName("255.255.255.255")); } catch (Exception ignored) {}
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback()) continue;
                for (InterfaceAddress a : n.getInterfaceAddresses())
                    if (a.getBroadcast() != null && !out.contains(a.getBroadcast())) out.add(a.getBroadcast());
            }
        } catch (Exception ignored) {}
        return out;
    }

    // The computer's folders as a tree of Nodes, from its /tree: every file and folder under Movies and
    // Series, with info.json and .srt files' contents in it. Null if it doesn't answer.
    static Node.Remote tree(Share s) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(s.link("tree")).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(20000);
            String body;
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                body = new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
            JSONArray entries = new JSONObject(body).getJSONArray("entries");
            Node.Remote root = new Node.Remote(s, "", true, 0, 0, null);
            for (int i = 0; i < entries.length(); i++) {
                JSONObject e = entries.getJSONObject(i);
                String path = e.getString("path");
                Node.Remote up = folder(root, s, path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : "");
                String name = path.substring(path.lastIndexOf('/') + 1);
                Node.Remote n = up.children.get(name);
                if (n == null) {
                    n = new Node.Remote(s, path, e.optBoolean("dir"), e.optLong("size"), e.optLong("mtime") * 1000, up);
                    up.children.put(name, n);
                }
                if (e.has("b64")) n.text = Base64.decode(e.getString("b64"), Base64.DEFAULT);
            }
            return root;
        } catch (Exception e) {
            return null;
        }
    }

    // The folder at path, made along the way if the list didn't have it first.
    private static Node.Remote folder(Node.Remote root, Share s, String path) {
        if (path.isEmpty()) return root;
        Node.Remote at = root;
        StringBuilder so = new StringBuilder();
        for (String part : path.split("/")) {
            if (so.length() > 0) so.append('/');
            so.append(part);
            Node.Remote next = at.children.get(part);
            if (next == null) at.children.put(part, next = new Node.Remote(s, so.toString(), true, 0, 0, at));
            at = next;
        }
        return at;
    }
}
