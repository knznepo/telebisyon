package com.knz.flixnet;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;

// For Telebisyon's web page (its FLIXNET page): the library as JSON, and the posters and backgrounds in
// it. Only apps signed with the same key can ask (the LIBRARY permission), and only those pictures can
// be read, nothing else on the drive.
//   call("library")                           -> {"json": the library, as Library.toJson}
//   content://com.knz.flixnet.library/image?path=<a poster or background's path>
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

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String path = uri.getQueryParameter("path");
        Library lib = Library.current;
        if (lib == null) lib = Library.scan(getContext());
        File f = path == null ? null : new File(path);
        if (f == null || !lib.isArt(f)) throw new FileNotFoundException("not a poster or background in the library");
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
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
