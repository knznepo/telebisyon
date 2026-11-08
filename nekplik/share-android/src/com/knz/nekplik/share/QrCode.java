package com.knz.nekplik.share;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

// A QR code for a short text, like the web version's link, to scan with a phone's camera. Plain Java,
// following the QR standard (ISO 18004): byte mode, medium error correction, versions 1 to 6 (up to 106
// bytes), one fixed mask. Gives the dark modules, without the quiet border.
final class QrCode {
    private static final int[] ECC_PER_BLOCK = {0, 10, 16, 26, 18, 24, 16}; // level M, by version
    private static final int[] BLOCKS = {0, 1, 1, 1, 2, 2, 4};

    static boolean[][] encode(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        for (int ver = 1; ver <= 6; ver++) {
            int capacity = dataCodewords(ver);
            if (4 + 8 + data.length * 8 <= capacity * 8) return draw(ver, codewords(data, ver, capacity));
        }
        throw new IllegalArgumentException("Too long for a QR code here: " + data.length + " bytes");
    }

    private static int rawModules(int ver) {
        int r = (16 * ver + 128) * ver + 64;
        if (ver >= 2) {
            int align = ver / 7 + 2;
            r -= (25 * align - 10) * align - 55;
        }
        return r;
    }

    private static int dataCodewords(int ver) { return rawModules(ver) / 8 - ECC_PER_BLOCK[ver] * BLOCKS[ver]; }

    // Mode, length, the bytes, the end marker, then padding: and the error correction, interleaved.
    private static byte[] codewords(byte[] text, int ver, int capacity) {
        Bits b = new Bits();
        b.add(4, 4); // byte mode
        b.add(text.length, 8);
        for (byte x : text) b.add(x & 0xFF, 8);
        b.add(0, Math.min(4, capacity * 8 - b.n));
        b.add(0, (8 - b.n % 8) % 8);
        for (int pad = 0xEC; b.n < capacity * 8; pad ^= 0xEC ^ 0x11) b.add(pad, 8);
        byte[] data = b.bytes();

        int blocks = BLOCKS[ver], eccLen = ECC_PER_BLOCK[ver], raw = rawModules(ver) / 8;
        int shortBlocks = blocks - raw % blocks, shortLen = raw / blocks;
        byte[] divisor = rsDivisor(eccLen);
        byte[][] all = new byte[blocks][];
        for (int i = 0, k = 0; i < blocks; i++) {
            byte[] dat = Arrays.copyOfRange(data, k, k + shortLen - eccLen + (i < shortBlocks ? 0 : 1));
            k += dat.length;
            byte[] block = Arrays.copyOf(dat, shortLen + 1);
            byte[] ecc = rsRemainder(dat, divisor);
            System.arraycopy(ecc, 0, block, block.length - eccLen, ecc.length);
            all[i] = block;
        }
        byte[] out = new byte[raw];
        for (int i = 0, k = 0; i < all[0].length; i++)
            for (int j = 0; j < all.length; j++)
                if (i != shortLen - eccLen || j >= shortBlocks) out[k++] = all[j][i];
        return out;
    }

    private static boolean[][] draw(int ver, byte[] data) {
        int size = ver * 4 + 17;
        boolean[][] dark = new boolean[size][size], fixed = new boolean[size][size];
        Grid g = new Grid(dark, fixed);
        for (int i = 0; i < size; i++) { g.set(6, i, i % 2 == 0); g.set(i, 6, i % 2 == 0); } // timing
        for (int[] c : new int[][]{{3, 3}, {size - 4, 3}, {3, size - 4}}) // finders
            for (int dy = -4; dy <= 4; dy++)
                for (int dx = -4; dx <= 4; dx++) {
                    int x = c[0] + dx, y = c[1] + dy, d = Math.max(Math.abs(dx), Math.abs(dy));
                    if (x >= 0 && x < size && y >= 0 && y < size) g.set(x, y, d != 2 && d != 4);
                }
        if (ver >= 2) { // the one alignment pattern of versions 2 to 6
            int p = size - 7;
            for (int dy = -2; dy <= 2; dy++)
                for (int dx = -2; dx <= 2; dx++) g.set(p + dx, p + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
        }
        final int mask = 0;
        format(g, size, mask);

        // The codewords, in two-module columns from the bottom right, up then down.
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++)
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean up = ((right + 1) & 2) == 0;
                    int y = up ? size - 1 - vert : vert;
                    if (!fixed[y][x] && i < data.length * 8) {
                        dark[y][x] = ((data[i >>> 3] >>> (7 - (i & 7))) & 1) != 0;
                        i++;
                    }
                }
        }
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++)
                if (!fixed[y][x] && (x + y) % 2 == 0) dark[y][x] = !dark[y][x]; // mask 0
        return dark;
    }

    // Which error correction (M) and mask, twice, with its own error correction.
    private static void format(Grid g, int size, int mask) {
        int data = (0 << 3) | mask; // M is 00
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) g.set(8, i, bit(bits, i));
        g.set(8, 7, bit(bits, 6));
        g.set(8, 8, bit(bits, 7));
        g.set(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) g.set(14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) g.set(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) g.set(8, size - 15 + i, bit(bits, i));
        g.set(8, size - 8, true); // always dark
    }

    private static boolean bit(int x, int i) { return ((x >>> i) & 1) != 0; }

    private static byte[] rsDivisor(int degree) {
        byte[] r = new byte[degree];
        r[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < r.length; j++) {
                r[j] = (byte) mul(r[j] & 0xFF, root);
                if (j + 1 < r.length) r[j] ^= r[j + 1];
            }
            root = mul(root, 0x02);
        }
        return r;
    }

    private static byte[] rsRemainder(byte[] data, byte[] divisor) {
        byte[] r = new byte[divisor.length];
        for (byte b : data) {
            int factor = (b ^ r[0]) & 0xFF;
            System.arraycopy(r, 1, r, 0, r.length - 1);
            r[r.length - 1] = 0;
            for (int i = 0; i < r.length; i++) r[i] ^= (byte) mul(divisor[i] & 0xFF, factor);
        }
        return r;
    }

    private static int mul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    private static final class Grid {
        final boolean[][] dark, fixed;
        Grid(boolean[][] d, boolean[][] f) { dark = d; fixed = f; }
        void set(int x, int y, boolean on) { dark[y][x] = on; fixed[y][x] = true; }
    }

    private static final class Bits {
        byte[] buf = new byte[256];
        int n;
        void add(int value, int count) {
            for (int i = count - 1; i >= 0; i--, n++) if (((value >>> i) & 1) != 0) buf[n >>> 3] |= (byte) (0x80 >>> (n & 7));
        }
        byte[] bytes() { return Arrays.copyOf(buf, n / 8); }
    }
}
