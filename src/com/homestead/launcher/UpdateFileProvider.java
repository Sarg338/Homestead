/*
 * Homestead - a replacement home screen for Fire TV.
 * Copyright (C) 2026 Homestead contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms
 * of the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version. It is distributed in
 * the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details. You should have received a copy of the GNU General
 * Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.homestead.launcher;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Minimal read-only provider that serves the downloaded update APK (and nothing else) to the
 * system installer. Needed because file:// URIs cannot be shared on API 24+ and this project
 * has no androidx FileProvider. Used only on the no-tunnel install path.
 */
public final class UpdateFileProvider extends ContentProvider {

    private static final String AUTHORITY = "com.homestead.launcher.updates";
    private static final String FILE_NAME = "homestead-update.apk";
    private static final String MIME = "application/vnd.android.package-archive";

    /** The content:// URI a caller passes to the installer for the downloaded APK. */
    static Uri uriFor(Context c, File f) {
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .path(f.getName())
                .build();
    }

    private File cacheDir() {
        Context c = getContext();
        File dir = c.getCacheDir();
        if (dir == null) dir = c.getExternalCacheDir();
        return dir;
    }

    /** Only ever resolves to the one known file, regardless of what the URI path claims. */
    private File resolve() {
        return new File(cacheDir(), FILE_NAME);
    }

    @Override
    public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = resolve();
        if (!f.exists()) throw new FileNotFoundException("no update file");
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        String[] cols = projection != null ? projection
                : new String[]{ OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE };
        MatrixCursor cur = new MatrixCursor(cols);
        File f = resolve();
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = FILE_NAME;
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
            else row[i] = null;
        }
        cur.addRow(row);
        return cur;
    }

    @Override public String getType(Uri uri) { return MIME; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
