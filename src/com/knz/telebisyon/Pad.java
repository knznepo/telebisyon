package com.knz.telebisyon;

import android.content.Context;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

// The /pad page's game controller, for RetroArch on the TV. The page keeps a WebSocket open to /pad/ws
// and says each change ("b 8 1": RetroPad A down); this passes it on to RetroArch's Network RetroPad,
// which listens on UDP 55400 for player 1, 55401 for player 2, and so on (Settings > Network >
// Network RetroPad, and User N Network RetroPad). No accessibility service: RetroArch reads these
// itself, as from a controller.
//
// Whatever is held is let go when the page goes away, changes player, or stops saying it's there.
//
// Menu goes to RetroArch's Network Commands instead (UDP 55355, Settings > Network > Network Commands):
// MENU_TOGGLE opens its Quick Menu in a game. (The TV's Back key doesn't, on every TV.)
final class Pad {
    static final int BASE_PORT = 55400, PLAYERS = 4, COMMAND_PORT = 55355;
    static final String[] RETROARCH = {"com.retroarch", "com.retroarch.aarch64", "com.retroarch.ra32"};
    private static final int JOYPAD = 1, ANALOG = 5; // RETRO_DEVICE_*

    // RetroArch listens on IPv4 only. (Android's InetAddress.getLoopbackAddress() is IPv6's ::1.)
    private static InetAddress local() throws Exception { return InetAddress.getByAddress(new byte[] {127, 0, 0, 1}); }

    private Pad() {}

    // RetroArch's package, if one is installed.
    static String retroarch(Context c) {
        for (String p : RETROARCH) {
            try { c.getPackageManager().getPackageInfo(p, 0); return p; }
            catch (Exception ignored) {}
        }
        return null;
    }

    // Whether something already holds this player's port: RetroArch, listening. Only a quick bind and
    // close, so it's asked only while the page waits for RetroArch.
    static boolean listening(int player) { return taken(BASE_PORT + player - 1); }

    // Whether RetroArch's Network Commands are on, the same way.
    static boolean commands() { return taken(COMMAND_PORT); }

    private static boolean taken(int port) {
        try (DatagramSocket probe = new DatagramSocket(null)) {
            probe.setReuseAddress(false);
            // On 127.0.0.1, as IPv4: RetroArch listens on IPv4's 0.0.0.0, which an IPv6 socket wouldn't meet.
            probe.bind(new InetSocketAddress(local(), port));
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    // The page's WebSocket, until it closes. The request's head is already read; s is closed after.
    static void serve(Socket s, InputStream in, String key) throws Exception {
        OutputStream out = s.getOutputStream();
        String accept = android.util.Base64.encodeToString(java.security.MessageDigest.getInstance("SHA-1")
                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)), android.util.Base64.NO_WRAP);
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
                + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        s.setTcpNoDelay(true);
        s.setSoTimeout(6000); // the page says "h" every 2 seconds; a phone that locks stops

        State st = new State();
        try (DatagramSocket udp = new DatagramSocket()) {
            st.udp = udp;
            while (true) {
                String text;
                try {
                    text = frame(in, out);
                } catch (SocketTimeoutException e) {
                    break;
                }
                if (text == null) break; // closed
                for (String m : text.split(";")) st.handle(m.trim());
            }
            st.release();
        }
    }

    // One text message from the page, or null when it closes. Pings are answered here.
    private static String frame(InputStream in, OutputStream out) throws Exception {
        while (true) {
            int b0 = in.read(), b1 = in.read();
            if (b0 < 0 || b1 < 0) return null;
            int op = b0 & 0x0f;
            long len = b1 & 0x7f;
            if (len == 126) len = (read(in) << 8) | read(in);
            else if (len == 127) { len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | read(in); }
            if (len > 4096) return null; // nothing the page says is this long
            byte[] mask = new byte[4];
            if ((b1 & 0x80) != 0) fill(in, mask);
            byte[] data = new byte[(int) len];
            fill(in, data);
            for (int i = 0; i < data.length; i++) data[i] ^= mask[i & 3];
            if (op == 8) { // close: say it back
                out.write(new byte[] {(byte) 0x88, 0});
                out.flush();
                return null;
            }
            if (op == 9) { // ping
                out.write(new byte[] {(byte) 0x8a, (byte) data.length});
                out.write(data);
                out.flush();
                continue;
            }
            if (op == 1) return new String(data, StandardCharsets.UTF_8);
            // pongs and anything else: nothing to do
        }
    }

    private static int read(InputStream in) throws Exception {
        int b = in.read();
        if (b < 0) throw new java.io.EOFException();
        return b;
    }

    private static void fill(InputStream in, byte[] b) throws Exception {
        for (int got = 0; got < b.length; ) {
            int n = in.read(b, got, b.length - got);
            if (n < 0) throw new java.io.EOFException();
            got += n;
        }
    }

    // What one page holds down, and where it goes.
    private static final class State {
        DatagramSocket udp;
        final InetAddress LOCAL;
        State() throws Exception { LOCAL = local(); }
        int player = 1, buttons; // RetroPad buttons held, one bit each
        final int[] analog = new int[4]; // left x, left y, right x, right y

        // "p 2": player 2. "b <id> <0|1>": a RetroPad button (B 0, Y 1, Select 2, Start 3, Up 4, Down 5,
        // Left 6, Right 7, A 8, X 9, L 10, R 11, L2 12, R2 13, L3 14, R3 15). "a <stick> <axis> <value>":
        // a stick (0 left, 1 right; 0 x, 1 y; -32767 to 32767). "m": RetroArch's menu. "h": still here.
        void handle(String m) throws Exception {
            String[] p = m.split(" ");
            switch (p[0]) {
                case "p": {
                    int n = Integer.parseInt(p[1]);
                    if (n < 1 || n > PLAYERS || n == player) return;
                    release();
                    player = n;
                    return;
                }
                case "b": {
                    int id = Integer.parseInt(p[1]);
                    if (id < 0 || id > 15) return;
                    boolean down = p[2].equals("1");
                    buttons = down ? buttons | (1 << id) : buttons & ~(1 << id);
                    send(JOYPAD, 0, id, down ? 1 : 0);
                    return;
                }
                case "a": {
                    int stick = Integer.parseInt(p[1]), axis = Integer.parseInt(p[2]);
                    if (stick < 0 || stick > 1 || axis < 0 || axis > 1) return;
                    int v = Math.max(-32767, Math.min(32767, Integer.parseInt(p[3])));
                    analog[stick * 2 + axis] = v;
                    send(ANALOG, stick, axis, v);
                    return;
                }
                case "m": {
                    release();
                    byte[] cmd = "MENU_TOGGLE\n".getBytes(StandardCharsets.US_ASCII);
                    udp.send(new DatagramPacket(cmd, cmd.length, LOCAL, COMMAND_PORT));
                    return;
                }
                default: // "h", or something this doesn't know
            }
        }

        void release() throws Exception {
            for (int id = 0; id < 16; id++)
                if ((buttons & (1 << id)) != 0) send(JOYPAD, 0, id, 0);
            buttons = 0;
            for (int i = 0; i < 4; i++)
                if (analog[i] != 0) { send(ANALOG, i / 2, i % 2, 0); analog[i] = 0; }
        }

        // RetroArch's struct remote_message as this device lays it out: four ints, then a 16-bit state,
        // padded to 20 bytes (only a message of exactly that size counts).
        void send(int device, int index, int id, int state) throws Exception {
            ByteBuffer b = ByteBuffer.allocate(20).order(ByteOrder.nativeOrder());
            b.putInt(player - 1).putInt(device).putInt(index).putInt(id).putShort((short) state);
            udp.send(new DatagramPacket(b.array(), 20, LOCAL, BASE_PORT + player - 1));
        }
    }
}
