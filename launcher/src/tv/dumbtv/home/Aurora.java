package tv.dumbtv.home;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * Background › Aurora: a full-canvas living atmosphere of wide 3-stop ribbons across the upper
 * two-thirds of the screen plus a subtle ambient reflection behind the app dock, over near black.
 * Greys and blacks, which would make a dull aurora, get classic teal, sapphire and violet.
 */
final class Aurora extends Drawable {
    static final int BASE = 0xFF07080D;
    /** Centre x, centre y, radius x, radius y (fractions of the screen), and peak alpha. */
    private static final float[][] BANDS = {
        {0.18f, 0.12f, 0.82f, 0.62f, 0.36f},
        {0.62f, 0.24f, 0.78f, 0.64f, 0.38f},
        {0.92f, 0.38f, 0.68f, 0.56f, 0.30f},
        {0.24f, 0.76f, 0.60f, 0.40f, 0.20f},
    };
    private static final int[] CLASSIC = {0xFF1CC6AE, 0xFF4466F5, 0xFF9442DA, 0xFF4466F5};

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint vignette = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] colors = CLASSIC.clone();
    private int vignetteHeight;

    /** Recolours the bands from {@code color}; cheap enough to call on every animation frame. */
    void setColor(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        if (hsv[1] < 0.25f || hsv[2] < 0.2f) {
            System.arraycopy(CLASSIC, 0, colors, 0, CLASSIC.length);
        } else {
            float s = Math.max(hsv[1], 0.52f), v = Math.max(hsv[2], 0.72f);
            // Keep the upper ribbons calm and atmospheric while the lower reflection echoes the app.
            colors[0] = Color.HSVToColor(new float[] {(hsv[0] + 345f) % 360f, s * 0.85f, Math.min(1f, v + 0.08f)});
            colors[1] = Color.HSVToColor(new float[] {hsv[0], s * 0.9f, v});
            colors[2] = Color.HSVToColor(new float[] {(hsv[0] + 28f) % 360f, s * 0.85f, v});
            colors[3] = Color.HSVToColor(new float[] {hsv[0], Math.min(1f, s * 0.95f), Math.min(1f, v + 0.05f)});
        }
        invalidateSelf();
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        canvas.drawColor(BASE);
        int w = b.width(), h = b.height();
        if (w == 0 || h == 0) return;
        for (int i = 0; i < BANDS.length; i++) {
            float[] band = BANDS[i];
            float cx = b.left + band[0] * w, cy = b.top + band[1] * h;
            float rx = band[2] * w, ry = band[3] * h;
            int c = colors[i];
            int inner = Color.argb(Math.round(band[4] * 255), Color.red(c), Color.green(c), Color.blue(c));
            int mid = Color.argb(Math.round(band[4] * 0.36f * 255), Color.red(c), Color.green(c), Color.blue(c));
            paint.setShader(new RadialGradient(cx, cy, rx,
                    new int[] {inner, mid, inner & 0x00FFFFFF},
                    new float[] {0f, 0.46f, 1f}, Shader.TileMode.CLAMP));
            canvas.save();
            canvas.scale(1f, ry / rx, cx, cy);
            canvas.drawCircle(cx, cy, rx, paint);
            canvas.restore();
        }
        if (vignetteHeight != h) {
            vignetteHeight = h;
            vignette.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                    new int[] {0x1407080D, 0x0007080D, 0x7A07080D, 0xD007080D},
                    new float[] {0f, 0.38f, 0.82f, 1f}, Shader.TileMode.CLAMP));
        }
        canvas.drawRect(b, vignette);
    }

    @Override
    public void setAlpha(int alpha) {}

    @Override
    public void setColorFilter(ColorFilter filter) {}

    @Override
    public int getOpacity() {
        return PixelFormat.OPAQUE;
    }
}
