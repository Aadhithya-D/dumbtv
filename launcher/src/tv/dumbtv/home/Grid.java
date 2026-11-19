package tv.dumbtv.home;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * Lays out same-size tiles in as many columns as fit the available width, wrapping to a new
 * row instead of scrolling sideways. Used in place of the horizontal strip when Options >
 * Layout is "grid"; column count follows the tile size setting, not a fixed number, so Small,
 * Medium and Large each pack a different number of columns into the same screen width.
 *
 * The outer vertical ScrollView does all the scrolling; this view only ever grows as tall as
 * its rows need.
 */
final class Grid extends ViewGroup {
    private final int hGap, vGap;
    /** Apple TV style: a fixed column count, and a tray drawn behind the first line. 0 = fit. */
    int fixedColumns;
    boolean tray;
    private final android.graphics.Paint trayPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Paint trayGlass = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Paint trayEdge = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Paint traySheen = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Paint bmpPaint = new android.graphics.Paint(
            android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);
    private final android.graphics.RectF trayRect = new android.graphics.RectF();
    private android.graphics.Bitmap glassBmp;

    void setGlassBitmap(android.graphics.Bitmap b) {
        glassBmp = b;
        invalidate();
    }

    /** Screen-space bounds of the first-row tray, or null before layout. */
    android.graphics.Rect trayScreenRect() {
        int n = getChildCount();
        if (!tray || n == 0 || getWidth() == 0) return null;
        int cols = Math.min(n, columns());
        View first = getChildAt(0), last = getChildAt(cols - 1);
        if (first.getWidth() == 0) return null;
        int padX = Ui.dp(getContext(), 18), padY = Ui.dp(getContext(), 16);
        int[] at = new int[2];
        getLocationOnScreen(at);
        int l = at[0] + first.getLeft() - padX;
        int t = at[1] + first.getTop() - padY;
        int rt = at[0] + last.getRight() + padX;
        int b = at[1] + first.getTop() + Math.round(first.getWidth() * 9f / 16f) + padY;
        return new android.graphics.Rect(l, t, rt, b);
    }

    Grid(Context c) {
        super(c);
        setClipChildren(false);
        setClipToPadding(false);
        hGap = Ui.dp(c, 18);
        vGap = Ui.dp(c, 18);
        trayPaint.setColor(0x36070A12);
        trayEdge.setStyle(android.graphics.Paint.Style.STROKE);
        trayEdge.setStrokeWidth(Ui.dp(c, 1));
        trayEdge.setColor(0x44FFFFFF);
        traySheen.setStyle(android.graphics.Paint.Style.STROKE);
        traySheen.setStrokeWidth(Ui.dp(c, 1));
    }

    @Override
    protected void dispatchDraw(android.graphics.Canvas canvas) {
        int n = getChildCount();
        if (tray && n > 0) {
            int cols = Math.min(n, columns());
            View first = getChildAt(0), last = getChildAt(cols - 1);
            float padX = Ui.dp(getContext(), 18), padY = Ui.dp(getContext(), 16), r = Ui.dp(getContext(), 28);
            float l = first.getLeft() - padX, t = first.getTop() - padY;
            // A tile keeps room under its card for a name; the tray hugs the 16:9 card itself.
            float rt = last.getRight() + padX, b = first.getTop() + first.getWidth() * 9f / 16f + padY;
            if (glassBmp != null && !glassBmp.isRecycled()) {
                trayRect.set(l, t, rt, b);
                canvas.drawBitmap(glassBmp, null, trayRect, bmpPaint);
            } else {
                canvas.drawRoundRect(l, t, rt, b, r, r, trayPaint);
                trayGlass.setShader(new android.graphics.LinearGradient(l, t, l, b,
                        new int[] {0x2EFFFFFF, 0x10FFFFFF}, null, android.graphics.Shader.TileMode.CLAMP));
                canvas.drawRoundRect(l, t, rt, b, r, r, trayGlass);
                canvas.drawRoundRect(l, t, rt, b, r, r, trayEdge);
                traySheen.setShader(new android.graphics.LinearGradient(l + r, t, rt - r, t,
                        new int[] {0x00FFFFFF, 0x6BFFFFFF, 0x00FFFFFF},
                        new float[] {0f, 0.5f, 1f}, android.graphics.Shader.TileMode.CLAMP));
                canvas.drawLine(l + r, t + Ui.dp(getContext(), 0.5f), rt - r, t + Ui.dp(getContext(), 0.5f), traySheen);
            }
        }
        super.dispatchDraw(canvas);
    }

    private int columns(int availableWidth, int childWidth) {
        if (fixedColumns > 0) return fixedColumns;
        return Math.max(1, (availableWidth + hGap) / (childWidth + hGap));
    }

    /** Columns in the current layout; 1 before the first layout pass or with no tiles. */
    int columns() {
        if (getChildCount() == 0 || getWidth() == 0) return 1;
        return columns(getWidth() - getPaddingLeft() - getPaddingRight(), getChildAt(0).getMeasuredWidth());
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int n = getChildCount();
        int cw = 0, ch = 0;
        for (int i = 0; i < n; i++) {
            View child = getChildAt(i);
            child.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
            cw = child.getMeasuredWidth();
            ch = child.getMeasuredHeight();
        }
        int height = getPaddingTop() + getPaddingBottom();
        if (n > 0) {
            int avail = Math.max(0, width - getPaddingLeft() - getPaddingRight());
            int cols = columns(avail, cw);
            int rows = (n + cols - 1) / cols;
            height += rows * ch + (rows - 1) * vGap;
        }
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int n = getChildCount();
        if (n == 0) return;
        View first = getChildAt(0);
        int cw = first.getMeasuredWidth(), ch = first.getMeasuredHeight();
        int avail = Math.max(0, (r - l) - getPaddingLeft() - getPaddingRight());
        int cols = columns(avail, cw);
        for (int i = 0; i < n; i++) {
            int col = i % cols, row = i / cols;
            int x = getPaddingLeft() + GridGeometry.columnLeft(avail, cw, hGap, cols, col);
            int y = getPaddingTop() + row * (ch + vGap);
            getChildAt(i).layout(x, y, x + cw, y + ch);
        }
    }
}
