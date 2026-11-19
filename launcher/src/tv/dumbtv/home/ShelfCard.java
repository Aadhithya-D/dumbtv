package tv.dumbtv.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewOutlineProvider;

/**
 * One title on the Top Shelf, laid out like Apple TV's Continue Watching: a wide 16:9 picture
 * with the app's badge in the top right, what to do next and the time left in the bottom left
 * over a soft shade, a thin progress bar, and the title's name under the card. Focus lifts the
 * card with a shadow, as on Apple TV, rather than drawing a ring. A title without a picture gets
 * a card in the app's colour with its name large.
 */
final class ShelfCard extends View {
    final Shelf.Entry entry;
    final int w, h, nameH;
    private final float radius;
    private final Paint art = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint status = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint detail = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint big = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint name = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final Drawable badge;
    private final int color;
    private Bitmap bitmap;
    boolean pinned;

    /**
     * @param badge the app's banner, drawn small in the top right; may be null
     * @param color the app's colour for a card without a picture
     */
    ShelfCard(Context c, Shelf.Entry entry, int w, int h, Drawable badge, int color) {
        super(c);
        this.entry = entry;
        this.w = w;
        this.h = h;
        this.badge = badge;
        this.color = color;
        nameH = Ui.dp(c, 30);
        radius = Ui.dp(c, 12);
        setFocusable(true);
        setClickable(true);
        status.setColor(0xFFFFFFFF);
        status.setTextSize(Ui.dp(c, 12));
        status.setTypeface(Ui.SEMIBOLD);
        detail.setColor(0xD9FFFFFF);
        detail.setTextSize(Ui.dp(c, 11));
        detail.setTypeface(Ui.MEDIUM);
        big.setColor(0xFFFFFFFF);
        big.setTextSize(Ui.dp(c, 22));
        big.setTypeface(Ui.SEMIBOLD);
        name.setTextSize(Ui.dp(c, 12));
        name.setTypeface(Ui.MEDIUM);
        name.setTextAlign(Paint.Align.CENTER);
        name.setShadowLayer(Ui.dp(c, 5), 0, Ui.dp(c, 1), 0xB3000000);
        shade.setShader(new LinearGradient(0, h * 0.42f, 0, h,
                new int[] {0x00000000, 0x66000000, 0xD0000000}, new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        // The shadow follows the card, not the name under it.
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, ShelfCard.this.w, ShelfCard.this.h, radius);
            }
        });
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
        setMeasuredDimension(w, h + nameH);
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previous) {
        super.onFocusChanged(focused, direction, previous);
        setPivotX(w / 2f);
        setPivotY(h / 2f);
        float s = focused ? 1.06f : 1f;
        animate().scaleX(s).scaleY(s).translationZ(focused ? Ui.dp(getContext(), 18) : 0)
                .setDuration(150).start();
        invalidate();
    }

    /** "Continue", "Pinned" or "Watched", and what is left: "38 min left", or the episode. */
    private String statusLine() {
        if (pinned) return "Pinned";
        return entry.duration > 0 && entry.progress > 0 ? "Continue" : "Recently watched";
    }

    private String detailLine() {
        if (entry.duration > 0 && entry.progress > 0 && entry.progress < entry.duration) {
            long min = Math.max(1, (entry.duration - entry.progress + 30_000) / 60_000);
            String left = min >= 60 ? (min / 60) + " h " + (min % 60) + " min left" : min + " min left";
            return entry.subtitle == null || entry.subtitle.isEmpty() ? left : entry.subtitle + "  ·  " + left;
        }
        return entry.subtitle == null ? "" : entry.subtitle;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float d = Ui.dp(getContext(), 1);
        box.set(0, 0, w, h);
        if (bitmap != null) {
            canvas.drawRoundRect(box, radius, radius, art);
        } else {
            fill.setShader(new LinearGradient(0, 0, w, h, new int[] {color, 0xFF101114},
                    null, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(box, radius, radius, fill);
            // The title large, two lines at most, where a picture's logo would be.
            float maxW = w - 32 * d;
            String t = entry.title;
            CharSequence one = TextUtils.ellipsize(t, big, maxW, TextUtils.TruncateAt.END);
            canvas.drawText(one, 0, one.length(), 16 * d, h * 0.42f, big);
        }
        canvas.drawRoundRect(box, radius, radius, shade);

        if (badge != null) {
            int bw = Math.round(48 * d), bh = Math.round(27 * d);
            int left = Math.round(w - bw - 12 * d), top = Math.round(12 * d);
            canvas.save();
            android.graphics.Path clip = new android.graphics.Path();
            clip.addRoundRect(new RectF(left, top, left + bw, top + bh), 5 * d, 5 * d, android.graphics.Path.Direction.CW);
            canvas.clipPath(clip);
            badge.setBounds(left, top, left + bw, top + bh);
            badge.draw(canvas);
            canvas.restore();
        }

        float pad = 16 * d;
        boolean progress = entry.duration > 0 && entry.progress > 0;
        float barY = h - 14 * d;
        float textBottom = progress ? barY - 9 * d : h - 14 * d;
        String det = detailLine();
        if (!det.isEmpty()) {
            CharSequence dl = TextUtils.ellipsize(det, detail, w - 2 * pad, TextUtils.TruncateAt.END);
            canvas.drawText(dl, 0, dl.length(), pad, textBottom, detail);
            textBottom -= detail.getTextSize() + 5 * d;
        }
        canvas.drawText(statusLine(), pad, textBottom, status);
        if (progress) {
            float bh = 3 * d, span = w - 2 * pad;
            float done = span * Math.min(1f, (float) entry.progress / entry.duration);
            bar.setColor(0x52FFFFFF);
            canvas.drawRoundRect(pad, barY, pad + span, barY + bh, bh, bh, bar);
            bar.setColor(0xFFFFFFFF);
            canvas.drawRoundRect(pad, barY, pad + done, barY + bh, bh, bh, bar);
        }

        // The name under the card: bright on the focused card, quiet on the others.
        name.setColor(isFocused() ? 0xFFFFFFFF : 0x99FFFFFF);
        CharSequence n = TextUtils.ellipsize(entry.title, name, w - 8 * d, TextUtils.TruncateAt.END);
        canvas.drawText(n, 0, n.length(), w / 2f, h + nameH - 9 * d, name);
    }
}
