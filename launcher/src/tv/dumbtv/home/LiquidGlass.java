package tv.dumbtv.home;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;

/**
 * Physically-inspired Liquid Glass renderer for API 25+ (no AGSL / RenderEffect required).
 * Samples the real wallpaper region behind a rounded-rect surface, applies a fast separable
 * backdrop blur, and refracts pixels along the rounded-rect SDF meniscus normal with subtle
 * R/G/B chromatic dispersion and dual directional Fresnel specular rim lighting.
 */
final class LiquidGlass {
    private LiquidGlass() {}

    /**
     * Renders a refractive glass bitmap matching {@code screenRect} (in screen coordinates)
     * over {@code wall}, which is displayed with {@code ImageView.ScaleType.CENTER_CROP}
     * inside {@code screenW x screenH}. Returns null if dimensions are invalid.
     */
    static Bitmap render(Bitmap wall, int screenW, int screenH, Rect screenRect, float radiusPx) {
        if (wall == null || wall.isRecycled() || screenW <= 0 || screenH <= 0) return null;
        int rw = screenRect.width(), rh = screenRect.height();
        if (rw <= 4 || rh <= 4) return null;

        // Half-resolution optical pass (~4x faster, silky smooth when bilinearly upscaled).
        final float scale = 0.5f;
        final int w = Math.max(8, Math.round(rw * scale));
        final int h = Math.max(8, Math.round(rh * scale));
        final int pad = 16; // extra pixels around the pill so rim refraction samples real surroundings
        final int sw = w + pad * 2;
        final int sh = h + pad * 2;

        // 1. Sample exact CENTER_CROP wallpaper region into sw x sh.
        Bitmap patch = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(patch);
        c.drawColor(0xFF0A0C12);
        float bw = wall.getWidth(), bh = wall.getHeight();
        float cropScale = Math.max(screenW / bw, screenH / bh);
        float dx = (screenW - bw * cropScale) * 0.5f;
        float dy = (screenH - bh * cropScale) * 0.5f;
        Matrix m = new Matrix();
        m.postScale(cropScale, cropScale);
        m.postTranslate(dx - screenRect.left, dy - screenRect.top);
        m.postScale(scale, scale);
        m.postTranslate(pad, pad);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        c.drawBitmap(wall, m, p);

        int[] sharp = new int[sw * sh];
        patch.getPixels(sharp, 0, sw, 0, 0, sw, sh);
        patch.recycle();

        // 2. Separable 2-pass box blur (approximates Gaussian frost).
        int[] blurred = blur(sharp, sw, sh, 5);
        blurred = blur(blurred, sw, sh, 4);

        // 3. Per-pixel rounded-rect SDF refraction + chromatic dispersion + Fresnel specular rim.
        int[] out = new int[w * h];
        float halfW = w * 0.5f;
        float halfH = h * 0.5f;
        float rad = Math.min(Math.max(2f, radiusPx * scale), Math.min(halfW, halfH));
        float bx = halfW - rad;
        float by = halfH - rad;
        float bevel = Math.min(halfH * 0.52f, 15f);

        // Directional light vectors (top-left primary, bottom-right secondary bounce).
        final float lx1 = -0.62f, ly1 = -0.78f;
        final float lx2 = 0.55f, ly2 = 0.83f;

        for (int y = 0; y < h; y++) {
            float py = (y + 0.5f) - halfH;
            float ay = Math.abs(py);
            float qy = ay - by;
            int row = y * w;
            for (int x = 0; x < w; x++) {
                float px = (x + 0.5f) - halfW;
                float ax = Math.abs(px);
                float qx = ax - bx;

                // Exact rounded-rect SDF (negative inside, 0 at boundary, positive outside).
                float ox = Math.max(qx, 0f), oy = Math.max(qy, 0f);
                float outside = (float) Math.hypot(ox, oy);
                float inside = Math.min(Math.max(qx, qy), 0f);
                float sdf = outside + inside - rad;

                if (sdf >= 0.75f) {
                    out[row + x] = 0;
                    continue;
                }
                // Anti-aliased coverage at the outer pill boundary.
                float alphaMask = sdf <= -0.75f ? 1f : clamp01(0.5f - sdf / 1.5f);

                // Outward unit normal at (px, py).
                float nx, ny;
                if (outside > 0.0001f) {
                    nx = (px >= 0f ? ox : -ox) / outside;
                    ny = (py >= 0f ? oy : -oy) / outside;
                } else if (qx > qy) {
                    nx = px >= 0f ? 1f : -1f;
                    ny = 0f;
                } else {
                    nx = 0f;
                    ny = py >= 0f ? 1f : -1f;
                }

                float distIn = Math.max(0f, -sdf);
                // Convex meniscus profile near the rim: bends light strongly at the curved edge.
                float rimT = distIn < bevel ? 1f - (distIn / bevel) : 0f;
                float lens = rimT * rimT * 11.5f;

                float baseX = x + pad;
                float baseY = y + pad;
                // Subtle R/G/B chromatic dispersion along the meniscus normal.
                float dispR = lens * 1.18f;
                float dispG = lens;
                float dispB = lens * 0.82f;

                // Blend from crisp refracted edge into softly diffused frosted interior.
                float sharpMix = rimT * rimT * 0.72f;
                int cR = sampleMix(blurred, sharp, sw, sh, baseX - nx * dispR, baseY - ny * dispR, sharpMix);
                int cG = sampleMix(blurred, sharp, sw, sh, baseX - nx * dispG, baseY - ny * dispG, sharpMix);
                int cB = sampleMix(blurred, sharp, sw, sh, baseX - nx * dispB, baseY - ny * dispB, sharpMix);

                float r = ((cR >> 16) & 0xFF);
                float g = ((cG >> 8) & 0xFF);
                float b = (cB & 0xFF);

                // Boost vibrancy slightly so underlying lights/colors glow richly through the glass.
                float luma = 0.299f * r + 0.587f * g + 0.114f * b;
                r = luma + (r - luma) * 1.28f;
                g = luma + (g - luma) * 1.28f;
                b = luma + (b - luma) * 1.28f;

                // Subtle dark optical tint + slight glass brightening on the curved meniscus.
                float tint = 0.76f + rimT * 0.14f;
                r = r * tint + 10f * 0.20f;
                g = g * tint + 14f * 0.20f;
                b = b * tint + 22f * 0.20f;

                // Dual directional Fresnel specular rim + subtle inner caustic.
                float rimLine = distIn < 1.8f ? (1f - distIn / 1.8f) : 0f;
                float innerGlow = rimT * rimT * 0.11f;
                float spec1 = Math.max(0f, nx * lx1 + ny * ly1);
                float spec2 = Math.max(0f, nx * lx2 + ny * ly2);
                float rimSpec = rimLine * (0.20f + 0.62f * spec1 * spec1 + 0.28f * spec2 * spec2)
                        + innerGlow * (0.45f + 0.55f * spec1);

                r = clamp255(r + rimSpec * 255f);
                g = clamp255(g + rimSpec * 255f);
                b = clamp255(b + rimSpec * 255f);

                int a = Math.round(alphaMask * 255f);
                out[row + x] = Color.argb(a, Math.round(r), Math.round(g), Math.round(b));
            }
        }

        Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        result.setPixels(out, 0, w, 0, 0, w, h);
        return result;
    }

    private static int sampleMix(int[] blurred, int[] sharp, int sw, int sh,
            float fx, float fy, float sharpMix) {
        int b = sampleBilinear(blurred, sw, sh, fx, fy);
        if (sharpMix <= 0.01f) return b;
        int s = sampleBilinear(sharp, sw, sh, fx, fy);
        float inv = 1f - sharpMix;
        int r = Math.round(((b >> 16) & 0xFF) * inv + ((s >> 16) & 0xFF) * sharpMix);
        int g = Math.round(((b >> 8) & 0xFF) * inv + ((s >> 8) & 0xFF) * sharpMix);
        int bl = Math.round((b & 0xFF) * inv + (s & 0xFF) * sharpMix);
        return (0xFF << 24) | (r << 16) | (g << 8) | bl;
    }

    private static int sampleBilinear(int[] px, int w, int h, float fx, float fy) {
        if (fx < 0f) fx = 0f;
        else if (fx > w - 1.001f) fx = w - 1.001f;
        if (fy < 0f) fy = 0f;
        else if (fy > h - 1.001f) fy = h - 1.001f;
        int x0 = (int) fx, y0 = (int) fy;
        float tx = fx - x0, ty = fy - y0;
        int i00 = y0 * w + x0;
        int c00 = px[i00], c10 = px[i00 + 1], c01 = px[i00 + w], c11 = px[i00 + w + 1];
        float w00 = (1f - tx) * (1f - ty);
        float w10 = tx * (1f - ty);
        float w01 = (1f - tx) * ty;
        float w11 = tx * ty;
        int r = Math.round(((c00 >> 16) & 0xFF) * w00 + ((c10 >> 16) & 0xFF) * w10
                + ((c01 >> 16) & 0xFF) * w01 + ((c11 >> 16) & 0xFF) * w11);
        int g = Math.round(((c00 >> 8) & 0xFF) * w00 + ((c10 >> 8) & 0xFF) * w10
                + ((c01 >> 8) & 0xFF) * w01 + ((c11 >> 8) & 0xFF) * w11);
        int b = Math.round((c00 & 0xFF) * w00 + (c10 & 0xFF) * w10
                + (c01 & 0xFF) * w01 + (c11 & 0xFF) * w11);
        return (0xFF << 24) | (r << 16) | (g << 8) | b;
    }

    /** Separable O(W*H) sliding-window box blur. */
    private static int[] blur(int[] src, int w, int h, int radius) {
        int[] tmp = new int[w * h];
        int[] dst = new int[w * h];
        int div = radius * 2 + 1;
        // Horizontal pass
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int sr = 0, sg = 0, sb = 0;
            for (int i = -radius; i <= radius; i++) {
                int c = src[row + clampInt(i, 0, w - 1)];
                sr += (c >> 16) & 0xFF;
                sg += (c >> 8) & 0xFF;
                sb += c & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                tmp[row + x] = (0xFF << 24) | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int outC = src[row + clampInt(x - radius, 0, w - 1)];
                int inC = src[row + clampInt(x + radius + 1, 0, w - 1)];
                sr += ((inC >> 16) & 0xFF) - ((outC >> 16) & 0xFF);
                sg += ((inC >> 8) & 0xFF) - ((outC >> 8) & 0xFF);
                sb += (inC & 0xFF) - (outC & 0xFF);
            }
        }
        // Vertical pass
        for (int x = 0; x < w; x++) {
            int sr = 0, sg = 0, sb = 0;
            for (int i = -radius; i <= radius; i++) {
                int c = tmp[clampInt(i, 0, h - 1) * w + x];
                sr += (c >> 16) & 0xFF;
                sg += (c >> 8) & 0xFF;
                sb += c & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                dst[y * w + x] = (0xFF << 24) | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int outC = tmp[clampInt(y - radius, 0, h - 1) * w + x];
                int inC = tmp[clampInt(y + radius + 1, 0, h - 1) * w + x];
                sr += ((inC >> 16) & 0xFF) - ((outC >> 16) & 0xFF);
                sg += ((inC >> 8) & 0xFF) - ((outC >> 8) & 0xFF);
                sb += (inC & 0xFF) - (outC & 0xFF);
            }
        }
        return dst;
    }

    private static int clampInt(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }

    private static float clamp255(float v) {
        return v < 0f ? 0f : Math.min(v, 255f);
    }
}
