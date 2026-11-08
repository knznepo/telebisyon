package com.knz.nekplik.share;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// A file or folder to share, wherever it is: a plain path (an SD card, a TV box's USB drive, this
// device's own storage), or a drive chosen in Android's picker. Phones keep USB drives away from plain
// paths, so there the picker is the only way in.
abstract class Item {
    String name;
    boolean dir;
    long size, mtime; // mtime in milliseconds

    abstract List<Item> list();

    // For reading from any point: a channel that can be positioned.
    abstract FileInputStream open() throws IOException;

    abstract String id(); // the same thing gives the same id, to check a path stays inside its folder

    Item child(String n) {
        for (Item i : list()) if (i.name.equals(n)) return i;
        return null;
    }

    static final class Path extends Item {
        final File file;

        Path(File f) {
            file = f;
            name = f.getName();
            dir = f.isDirectory();
            size = dir ? 0 : f.length();
            mtime = f.lastModified();
        }

        @Override
        List<Item> list() {
            List<Item> out = new ArrayList<>();
            String[] names = file.list();
            if (names == null) return out;
            Arrays.sort(names);
            for (String n : names) out.add(new Path(new File(file, n)));
            return out;
        }

        @Override
        FileInputStream open() throws IOException { return new FileInputStream(file); }

        @Override
        String id() {
            try { return file.getCanonicalPath(); } catch (IOException e) { return file.getAbsolutePath(); }
        }
    }

    // A document in a drive chosen with the picker (Storage Access Framework).
    static final class Doc extends Item {
        private static final String[] COLUMNS = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED};
        final ContentResolver cr;
        final Uri tree;
        final String doc;

        Doc(ContentResolver cr, Uri tree, String doc, String name, boolean dir, long size, long mtime) {
            this.cr = cr;
            this.tree = tree;
            this.doc = doc;
            this.name = name;
            this.dir = dir;
            this.size = size;
            this.mtime = mtime;
        }

        // The chosen drive or folder itself, or null if it isn't there now (unplugged).
        static Doc root(ContentResolver cr, Uri tree) {
            String id = DocumentsContract.getTreeDocumentId(tree);
            Uri u = DocumentsContract.buildDocumentUriUsingTree(tree, id);
            try (Cursor c = cr.query(u, COLUMNS, null, null, null)) {
                if (c == null || !c.moveToFirst()) return null;
                String name = c.getString(1);
                return new Doc(cr, tree, id, name == null ? id : name, true, 0, c.isNull(4) ? 0 : c.getLong(4));
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        List<Item> list() {
            List<Item> out = new ArrayList<>();
            if (!dir) return out;
            Uri u = DocumentsContract.buildChildDocumentsUriUsingTree(tree, doc);
            try (Cursor c = cr.query(u, COLUMNS, null, null, null)) {
                while (c != null && c.moveToNext()) {
                    String n = c.getString(1);
                    if (n == null) continue;
                    boolean d = DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2));
                    out.add(new Doc(cr, tree, c.getString(0), n, d, c.isNull(3) ? 0 : c.getLong(3), c.isNull(4) ? 0 : c.getLong(4)));
                }
            } catch (Exception ignored) {}
            java.util.Collections.sort(out, (a, b) -> a.name.compareTo(b.name));
            return out;
        }

        @Override
        FileInputStream open() throws IOException {
            ParcelFileDescriptor pfd = cr.openFileDescriptor(DocumentsContract.buildDocumentUriUsingTree(tree, doc), "r");
            if (pfd == null) throw new IOException("Can't open " + name);
            return new ParcelFileDescriptor.AutoCloseInputStream(pfd);
        }

        @Override
        String id() { return doc; }
    }

    static byte[] read(Item i, int max) throws IOException {
        try (InputStream in = i.open()) {
            byte[] b = new byte[(int) Math.min(max, i.size)];
            int got = 0;
            for (int r; got < b.length && (r = in.read(b, got, b.length - got)) > 0; ) got += r;
            return got == b.length ? b : Arrays.copyOf(b, got);
        }
    }
}
