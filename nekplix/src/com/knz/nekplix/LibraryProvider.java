package com.knz.nekplix;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.io.FileNotFoundException;

// For Telebisyon's web page (its NEKPLIX page): the library as JSON, and the posters and backgrounds in
// it. Only apps signed with the same key can ask (the LIBRARY permission), and only those pictures can
// be read, nothing else on the drive.
//   call("library")                           -> {"json": the library, as Library.toJson}
//   content://com.knz.nekplix.library/image?path=<a poster or background's key>
public class LibraryProvider extends ContentProvider {
    @Override
    public boolean onCreate() { return true; }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle b = new Bundle();
        if (!"library".equals(method)) return b;
        try {
            b.putString("json", Library.scan(getContext()).toJson(getContext()).toString());
        } catch (Exception e) {
            b.putString("error", String.valueOf(e.getMessage()));
        }
        return b;
    }

    // A picture on a drive is handed over as it is; one on a computer is passed through a pipe as it downloads.
    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String key = uri.getQueryParameter("path");
        Library lib = Library.current;
        if (lib == null) lib = Library.scan(getContext());
        Node art = key == null ? null : lib.art(key);
        if (art == null) throw new FileNotFoundException("not a poster or background in the library");
        if (art instanceof Node.Local) return ParcelFileDescriptor.open(((Node.Local) art).file, ParcelFileDescriptor.MODE_READ_ONLY);
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            new Thread(() -> {
                try (java.io.InputStream in = art.open();
                     java.io.OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    byte[] buf = new byte[16384];
                    for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                } catch (Exception ignored) {} // the computer went away: a cut-off picture
            }).start();
            return pipe[0];
        } catch (java.io.IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }

    @Override
    public String getType(Uri uri) { return null; }

    @Override
    public Cursor query(Uri uri, String[] p, String s, String[] a, String o) { return null; }

    @Override
    public Uri insert(Uri uri, ContentValues v) { return null; }

    @Override
    public int delete(Uri uri, String s, String[] a) { return 0; }

    @Override
    public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
