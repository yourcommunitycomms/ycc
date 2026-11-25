package org.communitycomms.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;

/** Title bar icons shared by the home screen and the full map (Shortcuts dots, the mesh signal bars). */
final class TitleIcons {
    private TitleIcons() {
    }

    /** Nine gold dots; a red dot in the corner when an update is waiting (or the black screen is not set up). */
    static BitmapDrawable shortcuts(Context c, boolean dot) {
        float d = c.getResources().getDisplayMetrics().density;
        int s = (int) (32 * d);
        Bitmap bm = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bm);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xfff0c75e);
        float step = s / 3.6f, r = s / 11f;
        for (int x = 0; x < 3; x++) for (int y = 0; y < 3; y++) cv.drawCircle(s / 2f + (x - 1) * step, s / 2f + (y - 1) * step, r, p);
        if (dot) {
            p.setColor(0xffff3b30);
            cv.drawCircle(s - s / 7f, s / 7f, s / 7f, p);
        }
        return new BitmapDrawable(c.getResources(), bm);
    }

    /** Four bars lit by my node's best neighbour signal; grey when not read. */
    static BitmapDrawable meshBars(Context c, int bars) {
        float d = c.getResources().getDisplayMetrics().density;
        int s = (int) (32 * d);
        Bitmap bm = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bm);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float w = s / 7f, gap = s / 14f, x = s / 2f - 2 * w - 1.5f * gap;
        for (int i = 0; i < 4; i++) {
            p.setColor(bars < 0 ? 0xff78909c : i < bars ? 0xff9ccc65 : 0x55cfd8dc);
            float h = s * (0.3f + 0.18f * i);
            cv.drawRect(x, s * 0.88f - h, x + w, s * 0.88f, p);
            x += w + gap;
        }
        return new BitmapDrawable(c.getResources(), bm);
    }

    /** The mesh radio icon shows on this screen (same rule as home). */
    static boolean meshShown(Context c) {
        return MeshRadio.onMeshLan(c) && (MeshRadio.available(c) || MeshRadio.detectedNode().length() > 0) && MeshRadio.iconOn()
                && new Prefs(c).appMode() != Prefs.MODE_INTERCOM;
    }
}