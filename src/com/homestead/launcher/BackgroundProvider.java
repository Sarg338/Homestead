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
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Receives a background photo from a computer, on any Fire OS:
 *
 *   adb push background.jpg /sdcard/Download/
 *   adb shell "content write --uri content://com.homestead.launcher.background/photo
 *       < /sdcard/Download/background.jpg"
 *
 * Fire OS 8 does not let adb write into the app's folder on external storage, but the
 * content command reaches the app itself. Exported so the shell can reach it; every caller
 * except the shell user and root is refused, and those already have far more access than
 * this. Write-only: nothing can be read back through it.
 */
public final class BackgroundProvider extends ContentProvider {

    static final String AUTHORITY = "com.homestead.launcher.background";
    private static final String TAG = "Homestead";
    /** adb's shell user and root. android.os.Process only names them from API 29. */
    private static final int SHELL_UID = 2000, ROOT_UID = 0;
    private static final long MAX_BYTES = 25L * 1024L * 1024L;

    /**
     * The last transfer's outcome, so PhotoPanel can tell whether the file it sent through
     * the tunnel was used. Wall-clock time of the result, and whether it was kept.
     */
    static volatile long lastResultAt;
    static volatile boolean lastOk;

    /** Where a photo received this way is kept, in app-private storage. */
    static File photoFile(Context c) { return new File(c.getFilesDir(), "background"); }

    @Override public boolean onCreate() { return true; }

    private static void checkCaller() {
        int uid = Binder.getCallingUid();
        if (uid != SHELL_UID && uid != ROOT_UID) {
            throw new SecurityException("only adb shell may set the background photo");
        }
    }

    private static boolean isPhoto(Uri uri) {
        return uri != null && "/photo".equals(uri.getPath());
    }

    /**
     * Hands the caller the write end of a pipe. A worker copies what arrives into a
     * temporary file and replaces the photo only once all of it has arrived within the
     * size limit, so a cut-off transfer never leaves half a picture.
     */
    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        checkCaller();
        if (!isPhoto(uri)) throw new FileNotFoundException("only " + AUTHORITY + "/photo");
        if (mode == null || mode.contains("r")) throw new FileNotFoundException("write only");
        final File dest = photoFile(getContext());
        final File part = new File(dest.getPath() + ".part");
        final ParcelFileDescriptor[] pipe;
        try {
            pipe = ParcelFileDescriptor.createPipe();
        } catch (IOException e) {
            throw new FileNotFoundException(String.valueOf(e));
        }
        new Thread(new Runnable() {
            public void run() {
                long total = 0;
                boolean ok = false;
                InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pipe[0]);
                FileOutputStream out = null;
                try {
                    out = new FileOutputStream(part);
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > MAX_BYTES) {
                            throw new IOException("larger than " + (MAX_BYTES >> 20) + " MB");
                        }
                        out.write(buf, 0, n);
                    }
                    out.close();
                    out = null;
                    // Replace the current photo only with something that is a picture: a
                    // cut-off or mangled transfer must not swap a good photo for a black screen.
                    if (!isImage(part)) throw new IOException("not a JPEG or PNG image");
                    ok = part.renameTo(dest);
                } catch (Throwable t) {
                    Log.w(TAG, "background photo not received: " + t);
                } finally {
                    try { in.close(); } catch (Throwable ignored) { }
                    if (out != null) try { out.close(); } catch (Throwable ignored) { }
                    if (!ok) part.delete();
                    lastOk = ok;
                    lastResultAt = System.currentTimeMillis();
                }
                Log.i(TAG, ok ? "background photo received, " + total + " bytes"
                        : "background photo discarded");
            }
        }, "homestead-photo-in").start();
        return pipe[1];
    }

    /** True if the file's header decodes as an image with a size. Reads only the header. */
    private static boolean isImage(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        return o.outWidth > 0 && o.outHeight > 0;
    }

    /** "content delete --uri …/photo" removes a photo received this way. */
    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        checkCaller();
        if (!isPhoto(uri)) return 0;
        return photoFile(getContext()).delete() ? 1 : 0;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args,
                        String sortOrder) {
        return null;
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
