package tv.dumbtv.home;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * Background › Aurora: three soft, wide bands across the top of the screen in shades of the
 * focused app's colour, over near black. Greys and blacks, which would make a dull aurora, get
 * the classic teal, blue and violet instead.
 */
final class Aurora extends Drawable {
    static final int BASE = 0xFF0A0608;
    /** Centre x, centre y, radius x, radius y (fractions of the screen), and alpha of each band. */
    private static final float[][] BANDS = {
        {0.25f, 0.06f, 0.70f, 0.32f, 0.55f},
        {0.60f, 0.14f, 0.65f, 0.30f, 0.55f},
        {0.88f, 0.20f, 0.55f, 0.28f, 0.38f},
    };
    private static final int[] CLASSIC = {0xFF28C8AA, 0xFF466EFF, 0xFF9646DC};

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] colors = CLASSIC.clone();

    /** Recolours the bands from {@code color}; cheap enough to call on every animation frame. */
    void setColor(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        if (hsv[1] < 0.25f || hsv[2] < 0.2f) {
            System.arraycopy(CLASSIC, 0, colors, 0, CLASSIC.length);
        } else {
            float s = Math.max(hsv[1], 0.55f), v = Math.max(hsv[2], 0.75f);
            colors[0] = Color.HSVToColor(new float[] {(hsv[0] + 350f) % 360f, s * 0.9f, Math.min(1f, v + 0.1f)});
            colors[1] = Color.HSVToColor(new float[] {hsv[0], s, v});
            colors[2] = Color.HSVToColor(new float[] {(hsv[0] + 25f) % 360f, s, v});
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
            paint.setShader(new RadialGradient(cx, cy, rx, new int[] {inner, inner & 0x00FFFFFF},
                    new float[] {0f, 1f}, Shader.TileMode.CLAMP));
            canvas.save();
            canvas.scale(1f, ry / rx, cx, cy);
            canvas.drawCircle(cx, cy, rx, paint);
            canvas.restore();
        }
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
