package org.communitycomms.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Minimal stand-in for AndroidX FileProvider: lets the camera app write a photo for us and lets
 * viewer apps open received files, using temporary per-URI grants. Two roots only:
 * content://org.communitycomms.app.files/received/NAME and .../camera/NAME.
 */
public final class FilesProvider extends ContentProvider {
    public static final String AUTHORITY = "org.communitycomms.app.files";

    public static Uri received(File f) {
        return Uri.parse("content://" + AUTHORITY + "/received/" + Uri.encode(f.getName()));
    }

    public static Uri camera(File f) {
        return Uri.parse("content://" + AUTHORITY + "/camera/" + Uri.encode(f.getName()));
    }

    /** A downloaded app update, for Android's installer. */
    public static Uri update(File f) {
        return Uri.parse("content://" + AUTHORITY + "/update/" + Uri.encode(f.getName()));
    }

    /** A mission video report the camera app writes. */
    public static Uri video(File f) {
        return Uri.parse("content://" + AUTHORITY + "/videos/" + Uri.encode(f.getName()));
    }

    /** Files made to share with other apps (ATAK map exports, map source XML). */
    public static Uri export(File f) {
        return Uri.parse("content://" + AUTHORITY + "/export/" + Uri.encode(f.getName()));
    }

    public static File exportDir(android.content.Context c) {
        File d = new File(c.getCacheDir(), "export");
        d.mkdirs();
        return d;
    }

    public static File cameraDir(android.content.Context c) {
        File d = new File(c.getCacheDir(), "camera");
        d.mkdirs();
        return d;
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        java.util.List<String> seg = uri.getPathSegments();
        if (seg.size() != 2 || seg.get(1).contains("/") || seg.get(1).startsWith(".")) {
            throw new FileNotFoundException(uri.toString());
        }
        File dir;
        if ("received".equals(seg.get(0))) dir = RadioService.receivedDir(getContext());
        else if ("camera".equals(seg.get(0))) dir = cameraDir(getContext());
        else if ("update".equals(seg.get(0))) dir = LanSync.updateDirFor(getContext());
        else if ("export".equals(seg.get(0))) dir = exportDir(getContext());
        else if ("videos".equals(seg.get(0))) dir = LanSync.videosDir(getContext());
        else throw new FileNotFoundException(uri.toString());
        return new File(dir, seg.get(1));
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = resolve(uri);
        boolean write = mode.contains("w");
        String root = uri.getPathSegments().get(0);
        if (write && !"camera".equals(root) && !"videos".equals(root)) throw new FileNotFoundException("read only");
        int m = write
                ? ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE
                : ParcelFileDescriptor.MODE_READ_ONLY;
        return ParcelFileDescriptor.open(f, m);
    }

    @Override
    public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        int dot = name == null ? -1 : name.lastIndexOf('.');
        String t = dot < 0 ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase());
        return t == null ? "application/octet-stream" : t;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String sel, String[] args, String sort) {
        File f;
        try {
            f = resolve(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        c.addRow(new Object[]{f.getName(), f.length()});
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues v) {
        return null;
    }

    @Override
    public int delete(Uri uri, String s, String[] a) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues v, String s, String[] a) {
        return 0;
    }
}
