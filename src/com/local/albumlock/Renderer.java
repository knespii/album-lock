package com.local.albumlock;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;

/** Draws an iOS-style lock screen: blurred, darkened artwork behind a large sharp cover. */
final class Renderer {
    /** Top edge of the cover, as a fraction of screen height (below the clock). */
    static final float COVER_TOP = 0.32f;
    /** Cover width as a fraction of screen width. */
    static final float COVER_WIDTH = 0.92f;

    private Renderer() {}

    static Bitmap render(Bitmap art, int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint filter = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

        // Background: crop the art to the screen's aspect, blur it at 1/10 size, scale it back up.
        int aw = art.getWidth(), ah = art.getHeight();
        int cropW = Math.min(aw, Math.round(ah * (float) w / h));
        int left = (aw - cropW) / 2;
        Bitmap crop = Bitmap.createBitmap(art, left, 0, cropW, ah);
        Bitmap small = Bitmap.createScaledBitmap(crop, Math.max(1, w / 10), Math.max(1, h / 10), true);
        Bitmap blurred = blur(small, 7, 3);
        c.drawBitmap(blurred, null, new Rect(0, 0, w, h), filter);
        c.drawColor(0x59000000); // darken so the clock and text stay readable
        if (crop != art) crop.recycle();
        small.recycle();
        blurred.recycle();

        // Foreground: large cover with rounded corners and a soft shadow.
        int size = Math.round(w * COVER_WIDTH);
        float x = (w - size) / 2f;
        float y = h * COVER_TOP;
        RectF dst = new RectF(x, y, x + size, y + size);
        float radius = size * 0.045f;

        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setColor(0xFF000000);
        shadow.setShadowLayer(size * 0.06f, 0, size * 0.025f, 0x99000000);
        c.drawRoundRect(dst, radius, radius, shadow);

        Path clip = new Path();
        clip.addRoundRect(dst, radius, radius, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        c.drawBitmap(art, null, dst, filter);
        c.restore();

        return out;
    }

    /** Repeated box blur (close to a Gaussian after 3 passes), edges clamped. */
    static Bitmap blur(Bitmap src, int radius, int passes) {
        int w = src.getWidth(), h = src.getHeight();
        int[] a = new int[w * h];
        int[] b = new int[w * h];
        src.getPixels(a, 0, w, 0, 0, w, h);
        for (int p = 0; p < passes; p++) {
            boxPass(a, b, w, h, radius, true);
            boxPass(b, a, w, h, radius, false);
        }
        return Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888);
    }

    private static void boxPass(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w;
        int len = horizontal ? w : h;
        int div = 2 * r + 1;
        for (int line = 0; line < lines; line++) {
            int rs = 0, gs = 0, bs = 0;
            for (int i = -r; i <= r; i++) {
                int px = in[index(line, clamp(i, len), w, horizontal)];
                rs += (px >> 16) & 0xFF; gs += (px >> 8) & 0xFF; bs += px & 0xFF;
            }
            for (int i = 0; i < len; i++) {
                out[index(line, i, w, horizontal)] = 0xFF000000 | ((rs / div) << 16) | ((gs / div) << 8) | (bs / div);
                int add = in[index(line, clamp(i + r + 1, len), w, horizontal)];
                int rem = in[index(line, clamp(i - r, len), w, horizontal)];
                rs += ((add >> 16) & 0xFF) - ((rem >> 16) & 0xFF);
                gs += ((add >> 8) & 0xFF) - ((rem >> 8) & 0xFF);
                bs += (add & 0xFF) - (rem & 0xFF);
            }
        }
    }

    private static int clamp(int i, int len) {
        return i < 0 ? 0 : (i >= len ? len - 1 : i);
    }

    private static int index(int line, int i, int w, boolean horizontal) {
        return horizontal ? line * w + i : i * w + line;
    }
}
