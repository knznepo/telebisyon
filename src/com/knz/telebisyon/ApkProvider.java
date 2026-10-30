package com.knz.telebisyon;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Locale;

// Hands an APK from the /files page to Android's installer, which can't read a file path itself
// (Files.install). Not exported: the installer can open only the one APK it was given.
public class ApkProvider extends ContentProvider {
    private static final String AUTHORITY = "com.knz.telebisyon.apk";

    static Uri uri(File f) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).path(f.getPath()).build();
    }

    private static File file(Uri uri) throws FileNotFoundException {
        File f = new File(uri.getPath());
        if (!f.getName().toLowerCase(Locale.ROOT).endsWith(".apk") || !f.isFile()) throw new FileNotFoundException(uri.toString());
        return f;
    }

    @Override
    public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) { return "application/vnd.android.package-archive"; }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        try {
            File f = file(uri);
            MatrixCursor m = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            m.addRow(new Object[]{f.getName(), f.length()});
            return m;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) { return null; }

    @Override
    public int delete(Uri uri, String selection, String[] args) { return 0; }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
