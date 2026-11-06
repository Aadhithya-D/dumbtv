package tv.dumbtv.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

/** One title on the Top Shelf: its picture, progress, a pin mark, and a white ring on focus. */
final class ShelfCard extends View {
    final Shelf.Entry entry;
    private final int w, h;
    private final float radius, stroke;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint art = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private Bitmap bitmap;
    boolean pinned;

    ShelfCard(Context c, Shelf.Entry entry, int w, int h) {
        super(c);
        this.entry = entry;
        this.w = w;
        this.h = h;
        radius = Ui.dp(c, 10);
        stroke = Ui.dp(c, 2.5f);
        setFocusable(true);
        setClickable(true);
        fill.setColor(0xFF1C1D21);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(stroke);
        ring.setColor(0xFFFFFFFF);
        text.setColor(Ui.TEXT);
        text.setTextSize(Ui.dp(c, 13));
        text.setTypeface(Ui.MEDIUM);
    }

    void setBitmap(Bitmap b) {
        bitmap = b;
        if (b != null) {
            BitmapShader s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            float scale = Math.max((float) w / b.getWidth(), (float) h / b.getHeight());
            Matrix m = new Matrix();
            m.setScale(scale, scale);
            m.postTranslate((w - b.getWidth() * scale) / 2f, (h - b.getHeight() * scale) / 2f);
            s.setLocalMatrix(m);
            art.setShader(s);
        }
        invalidate();
    }

    @Override
    protected void onMeasure(int ws, int hs) {
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previous) {
        super.onFocusChanged(focused, direction, previous);
        float s = focused ? 1.08f : 1f;
        animate().scaleX(s).scaleY(s).setDuration(110).start();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        box.set(0, 0, w, h);
        if (bitmap != null) {
            canvas.drawRoundRect(box, radius, radius, art);
        } else {
            canvas.drawRoundRect(box, radius, radius, fill);
            CharSequence t = TextUtils.ellipsize(entry.title, text, w - Ui.dp(getContext(), 20), TextUtils.TruncateAt.END);
            canvas.drawText(t, 0, t.length(), Ui.dp(getContext(), 10), h - Ui.dp(getContext(), 12), text);
        }
        if (entry.duration > 0 && entry.progress > 0) {
            float pad = Ui.dp(getContext(), 10), y = h - Ui.dp(getContext(), 8), bh = Ui.dp(getContext(), 3);
            float span = w - 2 * pad, done = span * Math.min(1f, (float) entry.progress / entry.duration);
            bar.setColor(0x66FFFFFF);
            canvas.drawRoundRect(pad, y, pad + span, y + bh, bh, bh, bar);
            bar.setColor(0xFFFFFFFF);
            canvas.drawRoundRect(pad, y, pad + done, y + bh, bh, bh, bar);
        }
        if (pinned) {
            float r = Ui.dp(getContext(), 9), cx = w - Ui.dp(getContext(), 16), cy = Ui.dp(getContext(), 16);
            bar.setColor(0xE6FFFFFF);
            canvas.drawCircle(cx, cy, r, bar);
            bar.setColor(0xFF111111);
            canvas.drawCircle(cx, cy, r * 0.38f, bar);
        }
        if (isFocused()) {
            float half = stroke / 2;
            canvas.drawRoundRect(half, half, w - half, h - half, radius - half, radius - half, ring);
        }
    }
}
