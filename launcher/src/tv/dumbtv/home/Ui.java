package tv.dumbtv.home;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.widget.TextView;

final class Ui {
    static final int TEXT = 0xFFF2F2F3;
    static final int MUTED = 0x99FFFFFF;
    static final int FAINT = 0x66FFFFFF;
    static final int SURFACE = 0xFF1C1D21;
    static final int DIALOG = android.R.style.Theme_DeviceDefault_Dialog_Alert;
    static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** Black on light accents, white on dark ones. */
    static int ink(int color) {
        return Color.luminance(color) > 0.5f ? 0xFF000000 : 0xFFFFFFFF;
    }

    static TextView text(Context c, float sp, int color, Typeface face) {
        TextView v = new TextView(c);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        v.setTypeface(face);
        v.setIncludeFontPadding(false);
        return v;
    }

    static GradientDrawable round(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    static GradientDrawable roundStroke(int fill, int stroke, int strokePx, float radius) {
        GradientDrawable d = round(fill, radius);
        d.setStroke(strokePx, stroke);
        return d;
    }

    /** Accent fill when focused, a faint fill otherwise. */
    static void focusStyle(TextView v, int accent, int idle, float radius) {
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_focused}, round(accent, radius));
        bg.addState(new int[0], round(idle, radius));
        v.setBackground(bg);
        v.setTextColor(focused(ink(accent), 0xD9FFFFFF));
    }

    /** Warm muted-amber glass when offline; fills solid amber with dark ink on focus. */
    static void offlineStyle(TextView v, float radius, int strokePx) {
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_focused}, round(0xF2FBBF24, radius));
        bg.addState(new int[0], roundStroke(0x24F59E0B, 0x58FBBF24, strokePx, radius));
        v.setBackground(bg);
        v.setTextColor(focused(0xFF141108, 0xFFFDE68A));
    }

    /**
     * A chip icon to go with {@link #focusStyle}: {@code shape} in the chip's text colour and
     * {@code mark} in the accent, both turning the ink colour on the accent fill when focused.
     */
    static Drawable chipIcon(Drawable shape, Drawable mark, int accent, int size) {
        return chipIconColors(shape, mark, ink(accent), 0xD9FFFFFF, accent, size);
    }

    static Drawable chipIconColors(Drawable shape, Drawable mark, int onInk, int offInk, int markOff, int size) {
        shape.mutate().setTintList(focused(onInk, offInk));
        mark.mutate().setTintList(focused(onInk, markOff));
        LayerDrawable d = new LayerDrawable(new Drawable[] {shape, mark});
        d.setBounds(0, 0, size, size);
        return d;
    }

    private static ColorStateList focused(int on, int off) {
        return new ColorStateList(new int[][] {{android.R.attr.state_focused}, {}}, new int[] {on, off});
    }
}
