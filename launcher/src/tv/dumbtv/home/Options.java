package tv.dumbtv.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvInputManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Options: categories on the left over the blurred wallpaper, the chosen category's settings on
 * one glass sheet on the right. Only settings that do something in the current setup are listed;
 * the rest collapse into one line saying why. The focused row turns into a white pill, shows
 * {@code ‹ value ›} and where it sits among its choices, and the footer says what it does.
 *
 * <p>Remote: Up/Down move, Right or OK enters a category, Left/Right/OK change a value, Left on
 * an action or Back returns to the categories, Back there returns home.
 */
public final class Options extends Activity {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int PILL = 0xFFF5F5F7;
    private static final int INK = 0xFF0B0B10;
    private static final int INK_SOFT = 0xB80B0B10;
    private static final int WARN = 0xFFFFB86B;
    /** The settings panel on the black page, and the secondary text sizes' colours on it. */
    private static final int SURFACE = 0xFF0F1013;
    private static final int SECONDARY = 0xB3FFFFFF;
    private static final int TERTIARY = 0x8CFFFFFF;
    private static final int WARN_INK = 0xFFB2530A;

    /** A setting or an action, and when it applies: null means it applies, "" hides it quietly. */
    private final class Entry {
        Config.Choice choice;
        final String label;
        final Runnable run;
        final String desc;
        final Supplier<String> when;
        Supplier<String> value;
        boolean warn;
        LinearLayout row;
        TextView name, val, chevL, chevR, chevEnd, go;
        View swatch;
        Marks marks;

        Entry(Config.Choice choice, String label, Runnable run, String desc, Supplier<String> when) {
            this.choice = choice;
            this.label = label;
            this.run = run;
            this.desc = desc;
            this.when = when;
        }

        String why() {
            if (choice == null && run == null) return "";
            return when == null ? null : when.get();
        }
    }

    private final class Section {
        final String title;
        final List<Entry> entries = new ArrayList<>();
        TextView head, note;

        Section(String title) {
            this.title = title;
        }
    }

    private final class Category {
        final String name, desc;
        final int icon;
        final boolean preview;
        final List<Section> sections = new ArrayList<>();
        TextView nav;
        ImageView navIcon;
        LinearLayout navRow, list;
        ScrollView scroll;

        Category(String name, String desc, int icon, boolean preview) {
            this.name = name;
            this.desc = desc;
            this.icon = icon;
            this.preview = preview;
        }
    }

    private final List<Category> cats = new ArrayList<>();
    private Config cfg;
    private int active;
    private TextView title, desc;
    private Preview preview;
    private View previewBox;
    private LinearLayout pinCard;
    private TextView pinAddress, pinBody;
    private Entry iconPack;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.initFonts(this);
        cfg = Config.get(this);
        buildCategories();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        root.addView(buildLeft(), leftParams());
        root.addView(buildSheet(), sheetParams());
        setContentView(root);

        loadPreview();
        loadIconPacks();
        select(0);
        refresh();
        cats.get(0).navRow.requestFocus();
    }

    // ---------------------------------------------------------------- content

    private void buildCategories() {
        Supplier<String> tvOnly = () -> tv() ? null : getString(R.string.opt_tv_only);
        Supplier<String> classicOnly = () -> tv() ? getString(R.string.opt_classic_only) : null;
        Supplier<String> needsImage = () -> image() ? null : getString(R.string.opt_needs_wallpaper);
        Supplier<String> needsInputs = () -> "off".equals(cfg.get(Config.INPUTS))
                ? getString(R.string.opt_inputs_hidden) : null;

        Category home = new Category(getString(R.string.opt_cat_home), getString(R.string.opt_cat_home_desc),
                R.drawable.opt_home, true);
        Section style = section(home, R.string.opt_sec_style);
        style.entries.add(choice(Config.STYLE, null));
        Section apps = section(home, R.string.opt_sec_apps);
        apps.entries.add(choice(Config.LAYOUT, null));
        apps.entries.add(choice(Config.COLUMNS, tvOnly));
        apps.entries.add(choice(Config.TILES, null));
        apps.entries.add(choice(Config.PEEK, tvOnly));
        apps.entries.add(choice(Config.POSITION, classicOnly));
        apps.entries.add(choice(Config.LABELS, classicOnly));
        cats.add(home);

        Category look = new Category(getString(R.string.opt_cat_look), getString(R.string.opt_cat_look_desc),
                R.drawable.chip_wallpaper, true);
        Section wall = section(look, R.string.opt_sec_wallpaper);
        wall.entries.add(choice(Config.BACKGROUND, null));
        wall.entries.add(action(getString(R.string.next_wallpaper), getString(R.string.desc_next_wallpaper),
                needsImage, () -> {
                    cfg.nextWallpaper();
                    loadPreview();
                }));
        wall.entries.add(choice(Config.ROTATE, needsImage));
        wall.entries.add(choice(Config.DIM, needsImage));
        Section colour = section(look, R.string.opt_sec_colour);
        colour.entries.add(choice(Config.ACCENT, null));
        colour.entries.add(choice(Config.TILE_STYLE, null));
        // Filled in once the installed packs are known (loadIconPacks); hidden until then.
        iconPack = new Entry(null, getString(R.string.choice_icon_pack), null,
                getString(R.string.desc_icon_pack), null);
        colour.entries.add(iconPack);
        colour.entries.add(choice(Config.CHIPS, null));
        cats.add(look);

        Category appsCat = new Category(getString(R.string.opt_cat_apps), getString(R.string.opt_cat_apps_desc),
                R.drawable.opt_apps, false);
        Section appList = section(appsCat, R.string.opt_sec_apps);
        appList.entries.add(action(getString(R.string.apps_on_home), getString(R.string.desc_apps_on_home),
                null, () -> pick(Catalog.APPS)));
        appList.entries.add(choice(Config.ORDER, null));
        appList.entries.add(choice(Config.ALL_APPS, () -> "on".equals(cfg.get(Config.LOCK))
                ? getString(R.string.opt_lock_on) : null));
        Section inputs = section(appsCat, R.string.opt_sec_inputs);
        inputs.entries.add(choice(Config.INPUTS, null));
        inputs.entries.add(action(getString(R.string.inputs_on_home), getString(R.string.desc_inputs_on_home),
                needsInputs, () -> pick(Catalog.INPUTS)));
        inputs.entries.add(choice(Config.SWITCH, needsInputs));
        cats.add(appsCat);

        Category shelf = new Category(getString(R.string.opt_cat_shelf), getString(R.string.opt_cat_shelf_desc),
                R.drawable.opt_shelf, false);
        Section shelfSec = section(shelf, R.string.opt_sec_shelf);
        shelfSec.entries.add(choice(Config.SHELF, tvOnly));
        shelfSec.entries.add(choice(Config.LIBRARY, null));
        cats.add(shelf);

        Category sys = new Category(getString(R.string.opt_cat_system), getString(R.string.opt_cat_system_desc),
                R.drawable.chip_settings, false);
        Section display = section(sys, R.string.opt_sec_display);
        display.entries.add(choice(Config.CLOCK, classicOnly));
        display.entries.add(choice(Config.MEMORY, null));
        Section household = section(sys, R.string.opt_sec_household);
        household.entries.add(choice(Config.LOCK, null));
        Section upkeep = section(sys, R.string.opt_sec_maintenance);
        upkeep.entries.add(action(getString(R.string.export_settings), getString(R.string.desc_export),
                null, this::export));
        upkeep.entries.add(action(getString(R.string.reset_layout), getString(R.string.desc_reset),
                null, this::reset));
        Entry amazon = action(getString(R.string.open_amazon_home), getString(R.string.desc_amazon), null, () -> {
            Guard.amazon(this);
            finish();
        });
        amazon.warn = true;
        upkeep.entries.add(amazon);
        cats.add(sys);
    }

    private Section section(Category c, int title) {
        Section s = new Section(getString(title));
        c.sections.add(s);
        return s;
    }

    private Entry choice(Config.Choice c, Supplier<String> when) {
        return new Entry(c, Text.title(this, c), null, describe(c.key), when);
    }

    private Entry action(String label, String desc, Supplier<String> when, Runnable run) {
        return new Entry(null, label, run, desc, when);
    }

    private String describe(String key) {
        int id = getResources().getIdentifier("desc_" + key, "string", getPackageName());
        return id == 0 ? "" : getString(id);
    }

    private boolean tv() {
        return "tv".equals(cfg.get(Config.STYLE));
    }

    private boolean image() {
        String b = cfg.get(Config.BACKGROUND);
        return "image".equals(b) || "adaptive-tint".equals(b);
    }

    // ---------------------------------------------------------------- left column

    private FrameLayout.LayoutParams leftParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(252), MATCH);
        lp.leftMargin = dp(56);
        lp.topMargin = dp(48);
        lp.bottomMargin = dp(40);
        return lp;
    }

    private View buildLeft() {
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setClipChildren(false);
        left.setClipToPadding(false);

        TextView heading = Ui.text(this, 30, Ui.TEXT, Ui.SEMIBOLD);
        heading.setText(R.string.options);
        heading.setLetterSpacing(-0.02f);
        left.addView(heading);
        TextView sub = Ui.text(this, 10.5f, TERTIARY, Ui.REGULAR);
        sub.setText("dumbtv home " + version());
        sub.setPadding(dp(1), dp(7), 0, 0);
        left.addView(sub);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        list.setClipToPadding(false);
        list.setPadding(0, dp(26), 0, 0);
        for (int i = 0; i < cats.size(); i++) list.addView(navRow(i));
        left.addView(list, new LinearLayout.LayoutParams(MATCH, WRAP));

        View spring = new View(this);
        left.addView(spring, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView label = Ui.text(this, 9, TERTIARY, Ui.MEDIUM);
        label.setText(getString(R.string.opt_preview).toUpperCase(Locale.getDefault()));
        label.setLetterSpacing(0.16f);
        label.setPadding(dp(2), 0, 0, dp(7));
        box.addView(label);
        preview = new Preview(this);
        box.addView(preview, new LinearLayout.LayoutParams(dp(240), dp(135)));
        previewBox = box;
        left.addView(box);
        return left;
    }

    private View navRow(int i) {
        Category c = cats.get(i);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setPadding(dp(8), 0, dp(12), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, dp(38));
        lp.bottomMargin = dp(3);
        row.setLayoutParams(lp);

        ImageView icon = new ImageView(this);
        icon.setImageResource(c.icon);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = dp(5.5f);
        icon.setPadding(pad, pad, pad, pad);
        row.addView(icon, new LinearLayout.LayoutParams(dp(23), dp(23)));

        TextView name = Ui.text(this, 14, SECONDARY, Ui.MEDIUM);
        name.setText(c.name);
        name.setSingleLine(true);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        nlp.leftMargin = dp(11);
        row.addView(name, nlp);

        c.navRow = row;
        c.nav = name;
        c.navIcon = icon;
        row.setOnFocusChangeListener((v, has) -> {
            if (has) select(i);
            styleNav();
            v.animate().scaleX(has ? 1.03f : 1f).scaleY(has ? 1.03f : 1f).setDuration(140).start();
        });
        row.setOnClickListener(v -> enter(i));
        row.setOnKeyListener((v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) return enter(i);
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) return true;
            if (code == KeyEvent.KEYCODE_DPAD_UP) return i == 0;
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) return i == cats.size() - 1;
            return false;
        });
        return row;
    }

    private void styleNav() {
        for (int i = 0; i < cats.size(); i++) {
            Category c = cats.get(i);
            boolean focused = c.navRow.isFocused();
            boolean here = i == active;
            int color = focused ? INK : here ? Ui.TEXT : SECONDARY;
            c.navRow.setBackground(focused ? Ui.round(PILL, dp(11))
                    : here ? Ui.round(0x1FFFFFFF, dp(11)) : null);
            c.navRow.setElevation(focused ? dp(8) : 0);
            c.nav.setTextColor(color);
            c.navIcon.setColorFilter(color);
            c.navIcon.setBackground(Ui.round(focused ? 0x140B0B10 : 0x14FFFFFF, dp(7)));
        }
    }

    // ---------------------------------------------------------------- sheet

    private FrameLayout.LayoutParams sheetParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(MATCH, MATCH);
        lp.leftMargin = dp(350);
        lp.rightMargin = dp(48);
        lp.topMargin = dp(36);
        lp.bottomMargin = dp(36);
        return lp;
    }

    private View buildSheet() {
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackground(Ui.roundStroke(SURFACE, 0x14FFFFFF, Math.max(1, dp(0.5f)), dp(22)));
        sheet.setClipToOutline(true);

        title = Ui.text(this, 22, Ui.TEXT, Ui.SEMIBOLD);
        title.setLetterSpacing(-0.015f);
        title.setPadding(dp(28), dp(26), dp(28), dp(4));
        sheet.addView(title);

        FrameLayout content = new FrameLayout(this);
        for (Category c : cats) {
            ScrollView sv = new ScrollView(this);
            sv.setFocusable(false);
            sv.setVerticalScrollBarEnabled(false);
            sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
            sv.setVerticalFadingEdgeEnabled(true);
            sv.setFadingEdgeLength(dp(20));
            sv.setClipChildren(false);
            LinearLayout list = new LinearLayout(this);
            list.setOrientation(LinearLayout.VERTICAL);
            list.setClipChildren(false);
            list.setClipToPadding(false);
            list.setPadding(dp(20), 0, dp(20), dp(30));
            fill(c, list);
            sv.addView(list, new FrameLayout.LayoutParams(MATCH, WRAP));
            sv.setVisibility(View.GONE);
            c.scroll = sv;
            c.list = list;
            content.addView(sv, new FrameLayout.LayoutParams(MATCH, MATCH));
        }
        sheet.addView(content, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        View hair = new View(this);
        hair.setBackgroundColor(0x1AFFFFFF);
        sheet.addView(hair, new LinearLayout.LayoutParams(MATCH, Math.max(1, dp(0.5f))));

        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        foot.setMinimumHeight(dp(56));
        foot.setPadding(dp(28), dp(10), dp(28), dp(12));
        desc = Ui.text(this, 11, SECONDARY, Ui.REGULAR);
        desc.setLineSpacing(0, 1.35f);
        desc.setMaxLines(2);
        desc.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        dlp.rightMargin = dp(20);
        foot.addView(desc, dlp);
        foot.addView(key("◀ ▶", getString(R.string.opt_key_change)));
        foot.addView(key("OK", getString(R.string.opt_key_open)));
        foot.addView(key("BACK", getString(R.string.opt_key_back)));
        sheet.addView(foot, new LinearLayout.LayoutParams(MATCH, WRAP));
        return sheet;
    }

    private View key(String cap, String what) {
        LinearLayout k = new LinearLayout(this);
        k.setGravity(Gravity.CENTER_VERTICAL);
        k.setPadding(dp(11), 0, 0, 0);
        TextView c = Ui.text(this, 7.5f, Ui.MUTED, Ui.SEMIBOLD);
        c.setText(cap);
        c.setPadding(dp(4.5f), dp(2), dp(4.5f), dp(2));
        c.setBackground(Ui.roundStroke(0, 0x47EBEBF5, Math.max(1, dp(0.75f)), dp(4)));
        k.addView(c);
        TextView t = Ui.text(this, 9.5f, TERTIARY, Ui.REGULAR);
        t.setText(what);
        t.setPadding(dp(4), 0, 0, 0);
        k.addView(t);
        return k;
    }

    private void fill(Category c, LinearLayout list) {
        boolean first = true;
        for (Section s : c.sections) {
            s.head = Ui.text(this, 9, TERTIARY, Ui.MEDIUM);
            s.head.setText(s.title.toUpperCase(Locale.getDefault()));
            s.head.setLetterSpacing(0.16f);
            s.head.setPadding(dp(9), dp(first ? 8 : 16), 0, dp(6));
            list.addView(s.head);
            first = false;
            for (Entry e : s.entries) list.addView(row(c, e));
            s.note = Ui.text(this, 10.5f, TERTIARY, Ui.REGULAR);
            s.note.setLineSpacing(0, 1.35f);
            s.note.setPadding(dp(9), dp(7), dp(9), 0);
            list.addView(s.note);
            if (c.sections.get(c.sections.size() - 1) == s && c.icon == R.drawable.opt_shelf) {
                list.addView(pinCard());
            }
        }
        if (c.icon == R.drawable.chip_settings) {
            TextView about = Ui.text(this, 10, TERTIARY, Ui.REGULAR);
            about.setLineSpacing(0, 1.45f);
            about.setPadding(dp(9), dp(16), dp(9), 0);
            about.setText(getString(R.string.about, cfg.dir + "/" + Config.FILE, Config.WALLPAPERS,
                    "/sdcard/Android/media/" + getPackageName() + "/", Library.FILE, Library.DIR));
            list.addView(about);
        }
    }

    private View pinCard() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        TextView head = Ui.text(this, 9, TERTIARY, Ui.MEDIUM);
        head.setText(getString(R.string.opt_sec_pin).toUpperCase(Locale.getDefault()));
        head.setLetterSpacing(0.16f);
        head.setPadding(dp(9), dp(16), 0, dp(6));
        wrap.addView(head);

        pinCard = new LinearLayout(this);
        pinCard.setOrientation(LinearLayout.VERTICAL);
        pinCard.setPadding(dp(15), dp(14), dp(15), dp(15));
        pinCard.setBackground(Ui.roundStroke(0x0DFFFFFF, 0x14FFFFFF, Math.max(1, dp(0.5f)), dp(12)));
        TextView t = Ui.text(this, 13, Ui.TEXT, Ui.MEDIUM);
        t.setText(R.string.opt_pin_title);
        pinCard.addView(t);
        pinAddress = Ui.text(this, 13, Ui.TEXT, Ui.SEMIBOLD);
        pinAddress.setPadding(0, dp(7), 0, 0);
        pinCard.addView(pinAddress);
        pinBody = Ui.text(this, 11, SECONDARY, Ui.REGULAR);
        pinBody.setLineSpacing(0, 1.4f);
        pinBody.setPadding(0, dp(6), 0, 0);
        pinCard.addView(pinBody);
        wrap.addView(pinCard, new LinearLayout.LayoutParams(MATCH, WRAP));
        return wrap;
    }

    private View row(Category c, Entry e) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setPadding(dp(15), 0, dp(15), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, dp(36));
        lp.bottomMargin = dp(1);
        row.setLayoutParams(lp);

        e.name = Ui.text(this, 13.5f, Ui.TEXT, Ui.REGULAR);
        e.name.setText(e.label);
        e.name.setSingleLine(true);
        row.addView(e.name, new LinearLayout.LayoutParams(0, WRAP, 1f));

        e.chevL = chevron("‹");
        row.addView(e.chevL);
        e.swatch = new View(this);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(11), dp(11));
        slp.rightMargin = dp(7);
        row.addView(e.swatch, slp);
        e.val = Ui.text(this, 12.5f, SECONDARY, Ui.REGULAR);
        e.val.setSingleLine(true);
        row.addView(e.val);
        e.chevR = chevron("›");
        row.addView(e.chevR);
        e.marks = new Marks(this);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(WRAP, dp(14));
        mlp.leftMargin = dp(2);
        row.addView(e.marks, mlp);
        e.chevEnd = chevron("›");
        row.addView(e.chevEnd);
        e.go = Ui.text(this, 15, Ui.FAINT, Ui.REGULAR);
        e.go.setText("›");
        e.go.setPadding(dp(8), 0, 0, dp(1));
        row.addView(e.go);

        e.row = row;
        row.setOnFocusChangeListener((v, has) -> {
            show(e);
            v.animate().scaleX(has ? 1.02f : 1f).scaleY(has ? 1.02f : 1f).setDuration(140).start();
            if (has) {
                desc.setText(e.desc);
                c.scroll.post(() -> reveal(c, row));
            }
        });
        row.setOnClickListener(v -> {
            if (e.choice != null) step(e, 1);
            else if (e.run != null) e.run.run();
        });
        row.setOnKeyListener((v, code, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            boolean choice = e.choice != null;
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (choice) return step(e, -1);
                return backToNav();
            }
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) return !choice || step(e, 1);
            if (code == KeyEvent.KEYCODE_DPAD_UP && firstRow(c) == row) return backToNav();
            if (code == KeyEvent.KEYCODE_DPAD_DOWN && lastRow(c) == row) return true;
            return false;
        });
        return row;
    }

    /** Scrolls just enough to show the row with a margin; the first row shows the top, the last the end. */
    private void reveal(Category c, View row) {
        ScrollView sv = c.scroll;
        int y = sv.getScrollY(), h = sv.getHeight(), margin = dp(44);
        int max = Math.max(0, c.list.getHeight() - h);
        if (row == firstRow(c)) y = 0;
        else if (row.getTop() - margin < y) y = row.getTop() - margin;
        else if (row.getBottom() + margin > y + h) y = row.getBottom() + margin - h;
        y = Math.max(0, Math.min(max, y));
        // Start the view on a whole row or heading, never on half of one under the title.
        if (y > 0) {
            for (int i = 0; i < c.list.getChildCount(); i++) {
                View v = c.list.getChildAt(i);
                if (v.getVisibility() == View.VISIBLE && v.getTop() >= y) {
                    if (v.getTop() <= max && v.getTop() <= row.getTop()) y = v.getTop();
                    break;
                }
            }
        }
        sv.smoothScrollTo(0, y);
    }

    private TextView chevron(String s) {
        TextView t = Ui.text(this, 14, INK_SOFT, Ui.REGULAR);
        t.setText(s);
        t.setPadding(dp(7), 0, dp(7), dp(1));
        return t;
    }

    /** Paints one row for its value and whether it has the focus. */
    private void show(Entry e) {
        if (e.row == null) return;
        boolean f = e.row.isFocused();
        boolean choice = e.choice != null;
        e.row.setBackground(f ? Ui.round(PILL, dp(10)) : null);
        e.row.setElevation(f ? dp(8) : 0);
        e.name.setTextColor(e.warn ? (f ? WARN_INK : WARN) : f ? INK : Ui.TEXT);
        e.name.setTypeface(f ? Ui.MEDIUM : Ui.REGULAR);
        e.go.setVisibility(choice ? View.GONE : View.VISIBLE);
        e.go.setTextColor(f ? INK_SOFT : TERTIARY);
        e.chevL.setVisibility(choice && f ? View.VISIBLE : View.GONE);
        e.chevR.setVisibility(choice && f ? View.VISIBLE : View.GONE);
        e.chevEnd.setVisibility(View.GONE);
        e.val.setTextColor(f ? 0xD90B0B10 : SECONDARY);
        if (!choice) {
            String v = e.value == null ? null : e.value.get();
            e.val.setText(v == null ? "" : v);
            e.val.setVisibility(v == null ? View.GONE : View.VISIBLE);
            e.swatch.setVisibility(View.GONE);
            e.marks.setVisibility(View.GONE);
            return;
        }
        Config.Choice c = e.choice;
        String value = value(c);
        int at = Math.max(0, Arrays.asList(c.values).indexOf(value));
        boolean accent = c == Config.ACCENT;
        // Accent: ‹ swatches ›, so the right chevron moves after them.
        e.chevR.setVisibility(choice && f && !accent ? View.VISIBLE : View.GONE);
        e.chevEnd.setVisibility(accent && f ? View.VISIBLE : View.GONE);
        e.val.setText(Text.name(this, c, value));
        e.val.setVisibility(accent && f ? View.GONE : View.VISIBLE);
        e.swatch.setVisibility(accent && !f ? View.VISIBLE : View.GONE);
        if (accent) {
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(Config.accentColor(value));
            dot.setStroke(Math.max(1, dp(0.5f)), 0x4DFFFFFF);
            e.swatch.setBackground(dot);
            int[] colors = new int[c.values.length];
            for (int i = 0; i < colors.length; i++) colors[i] = Config.accentColor(c.values[i]);
            e.marks.swatches(colors, at);
        } else {
            e.marks.dots(c.values.length, at);
        }
        e.marks.setVisibility(f && c.values.length > 1 && c.values.length <= 10 ? View.VISIBLE : View.GONE);
    }

    // ---------------------------------------------------------------- state

    private void select(int i) {
        active = i;
        for (int j = 0; j < cats.size(); j++) {
            cats.get(j).scroll.setVisibility(j == i ? View.VISIBLE : View.GONE);
        }
        Category c = cats.get(i);
        title.setText(c.name);
        desc.setText(c.desc);
        previewBox.setVisibility(c.preview ? View.VISIBLE : View.INVISIBLE);
        c.scroll.scrollTo(0, 0);
        styleNav();
    }

    private boolean enter(int i) {
        select(i);
        View first = firstRow(cats.get(i));
        if (first != null) first.requestFocus();
        return true;
    }

    private boolean backToNav() {
        cats.get(active).navRow.requestFocus();
        desc.setText(cats.get(active).desc);
        return true;
    }

    private View firstRow(Category c) {
        for (Section s : c.sections) {
            for (Entry e : s.entries) if (e.row.getVisibility() == View.VISIBLE) return e.row;
        }
        return null;
    }

    private View lastRow(Category c) {
        View last = null;
        for (Section s : c.sections) {
            for (Entry e : s.entries) if (e.row.getVisibility() == View.VISIBLE) last = e.row;
        }
        return last;
    }

    /** Shows the rows that apply, folds the rest into one line per section, repaints. */
    private void refresh() {
        for (Category c : cats) {
            for (Section s : c.sections) {
                Map<String, List<String>> hidden = new LinkedHashMap<>();
                int shown = 0;
                for (Entry e : s.entries) {
                    String why = e.why();
                    boolean on = why == null;
                    e.row.setVisibility(on ? View.VISIBLE : View.GONE);
                    if (on) shown++;
                    else if (!why.isEmpty()) {
                        List<String> names = hidden.get(why);
                        if (names == null) hidden.put(why, names = new ArrayList<>());
                        names.add(e.label);
                    }
                    show(e);
                }
                StringBuilder note = new StringBuilder();
                for (Map.Entry<String, List<String>> h : hidden.entrySet()) {
                    if (note.length() > 0) note.append('\n');
                    note.append(TextUtils.join(", ", h.getValue())).append(" — ").append(h.getKey()).append('.');
                }
                s.note.setText(note);
                s.note.setVisibility(note.length() > 0 ? View.VISIBLE : View.GONE);
                s.head.setVisibility(shown > 0 || note.length() > 0 ? View.VISIBLE : View.GONE);
            }
        }
        refreshPin();
        preview.invalidate();
        styleNav();
        View f = getCurrentFocus();
        if (f == null || !f.isShown()) cats.get(active).navRow.requestFocus();
    }

    private void refreshPin() {
        if (pinCard == null) return;
        boolean on = tv() && !"off".equals(cfg.get(Config.SHELF));
        String ip = on ? address() : null;
        pinAddress.setVisibility(ip != null ? View.VISIBLE : View.GONE);
        if (ip != null) pinAddress.setText("http://" + ip + ":" + PinServer.PORT);
        pinBody.setText(!on ? R.string.opt_pin_off : ip == null ? R.string.opt_pin_offline : R.string.opt_pin_body);
    }

    /** The TV's address on the home network, for the phone to reach the pin page. */
    private static String address() {
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback() || n.isVirtual()) continue;
                for (InetAddress a : Collections.list(n.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
            // No interfaces readable: the card says the TV is offline.
        }
        return null;
    }

    /**
     * The value the home screen acts on. Top-right buttons never chosen follow the home style
     * (icons in Apple TV style, see Home.iconChips), so show that rather than the stored default.
     */
    private String value(Config.Choice c) {
        if (c == Config.CHIPS && cfg.get(c.key, "").isEmpty()) return tv() ? "icons" : "text";
        return cfg.get(c);
    }

    private boolean step(Entry e, int by) {
        Config.Choice c = e.choice;
        int n = c.values.length;
        // Nothing to switch to, as Icon pack with none installed: keep what is saved, so a pack
        // that comes back is still the chosen one.
        if (n < 2) return true;
        int i = Arrays.asList(c.values).indexOf(value(c));
        cfg.set(c.key, c.values[((i + by) % n + n) % n]);
        if (c == Config.BACKGROUND) loadPreview();
        refresh();
        if (c == Config.LOCK && "on".equals(cfg.get(c))) {
            Toast.makeText(this, getString(R.string.lock_on, (int) (Home.UNLOCK_MS / 1000)),
                    Toast.LENGTH_LONG).show();
        }
        return true;
    }

    @Override
    public void onBackPressed() {
        View f = getCurrentFocus();
        for (Category c : cats) {
            if (c.navRow == f) {
                super.onBackPressed();
                return;
            }
        }
        backToNav();
    }

    @Override
    protected void onResume() {
        super.onResume();
        cfg.refresh();
        refresh();
    }

    // ---------------------------------------------------------------- background work

    /** The wallpaper, small, for the preview of the home screen. */
    private void loadPreview() {
        final File file = cfg.wallpaper();
        new Thread(() -> {
            final Bitmap sharp = file == null ? null : decode(file, 480);
            runOnUiThread(() -> {
                if (!isDestroyed()) preview.setWallpaper(sharp);
            });
        }, "options-preview").start();
    }

    private static Bitmap decode(File f, int width) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0) return null;
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= width) o.inSampleSize *= 2;
        o.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    private void loadIconPacks() {
        // Finding the packs opens every theme app's resources: keep it off the UI thread.
        new Thread(() -> {
            Config.Choice packs = IconPack.choice(IconPack.installed(getPackageManager()),
                    getString(R.string.icon_pack_off), getString(R.string.icon_pack_none));
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                iconPack.choice = packs;
                refresh();
            });
        }, "icon-packs").start();
    }

    // ---------------------------------------------------------------- actions

    private void pick(String kind) {
        List<Item> items = Catalog.APPS.equals(kind)
                ? Catalog.apps(this)
                : Catalog.inputs(this, (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE));
        CharSequence[] names = new CharSequence[items.size()];
        boolean[] on = new boolean[items.size()];
        for (int i = 0; i < items.size(); i++) {
            names[i] = Catalog.label(cfg, items.get(i));
            on[i] = Catalog.shown(cfg, kind, items.get(i));
        }
        new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle(Catalog.APPS.equals(kind) ? R.string.apps_on_home : R.string.inputs_on_home)
                .setMultiChoiceItems(names, on, (d, which, checked) ->
                        Catalog.setShown(cfg, kind, items.get(which), checked))
                .setPositiveButton(R.string.done, null)
                .show();
    }

    private void export() {
        File copy = cfg.export();
        Toast.makeText(this, copy == null ? getString(R.string.export_failed)
                : getString(R.string.export_done, copy.getPath()), Toast.LENGTH_LONG).show();
    }

    private void reset() {
        new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle(R.string.reset_title)
                .setMessage(R.string.reset_message)
                .setPositiveButton(R.string.reset, (d, w) -> cfg.removePrefix("name.", "apps.", "inputs.", IconPack.CHOSEN))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    // ---------------------------------------------------------------- small views

    /** Where a value sits among its choices: dots, or the accent colours with the chosen one ringed. */
    private static final class Marks extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int[] colors;
        private int count, at;
        private final float d;

        Marks(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
        }

        void dots(int count, int at) {
            this.colors = null;
            this.count = count;
            this.at = at;
            requestLayout();
            invalidate();
        }

        void swatches(int[] colors, int at) {
            this.colors = colors;
            this.count = colors.length;
            this.at = at;
            requestLayout();
            invalidate();
        }

        private float size() {
            return (colors != null ? 11 : 3.5f) * d;
        }

        private float gap() {
            return (colors != null ? 6 : 3) * d;
        }

        @Override
        protected void onMeasure(int w, int h) {
            float pad = colors != null ? 3 * d : 0;
            setMeasuredDimension(Math.round(count * size() + (count - 1) * gap() + 2 * pad),
                    MeasureSpec.getSize(h));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float s = size(), g = gap(), cy = getHeight() / 2f;
            float x = colors != null ? 3 * d : 0;
            for (int i = 0; i < count; i++) {
                float cx = x + s / 2f;
                paint.setStyle(Paint.Style.FILL);
                if (colors != null) {
                    paint.setColor(colors[i]);
                    canvas.drawCircle(cx, cy, s / 2f, paint);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(0.8f * d);
                    paint.setColor(0x330B0B10);
                    canvas.drawCircle(cx, cy, s / 2f, paint);
                    paint.setStyle(Paint.Style.FILL);
                    if (i == at) {
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setStrokeWidth(1.2f * d);
                        paint.setColor(INK);
                        canvas.drawCircle(cx, cy, s / 2f + 2.2f * d, paint);
                    }
                } else {
                    paint.setColor(INK);
                    paint.setAlpha(i == at ? 230 : 56);
                    canvas.drawCircle(cx, cy, s / 2f, paint);
                }
                x += s + g;
            }
        }
    }

    /** A small drawing of the home screen as the current settings would lay it out. */
    private final class Preview extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF r = new RectF();
        private final Rect src = new Rect();
        private Bitmap wall;
        private final int[] palette = {0xFFE5322D, 0xFFF4F4F4, 0xFF26262B, 0xFF3355FF, 0xFFC2213B,
            0xFF0A8FD6, 0xFF7C3AED, 0xFF1D4ED8, 0xFFF97316, 0xFF16A34A};

        Preview(Context c) {
            super(c);
            setClipToOutline(true);
            setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View v, android.graphics.Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(11));
                }
            });
            setElevation(dp(10));
        }

        void setWallpaper(Bitmap b) {
            wall = b;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            paint.setStyle(Paint.Style.FILL);
            paint.setAlpha(255);
            boolean img = image() && wall != null;
            if (img) {
                // Centre-crop the wallpaper into the frame.
                float scale = Math.max((float) w / wall.getWidth(), (float) h / wall.getHeight());
                int sw = Math.round(w / scale), sh = Math.round(h / scale);
                int sx = (wall.getWidth() - sw) / 2, sy = (wall.getHeight() - sh) / 2;
                src.set(sx, sy, sx + sw, sy + sh);
                r.set(0, 0, w, h);
                c.drawBitmap(wall, src, r, paint);
                int dim;
                try {
                    dim = Integer.parseInt(cfg.get(Config.DIM));
                } catch (NumberFormatException e) {
                    dim = 40;
                }
                c.drawColor((Math.round(dim * 2.55f) << 24));
            } else {
                c.drawColor(cfg.background());
            }

            boolean tv = tv();
            float u = w / 240f;
            // Top-right buttons.
            boolean icons = "icons".equals(value(Config.CHIPS));
            int n = icons ? 4 : 3;
            float itemW = icons ? 5 * u : 14 * u, gap = 3 * u, padC = 3 * u;
            float capW = n * itemW + (n - 1) * gap + 2 * padC;
            r.set(w - 9 * u - capW, 8 * u, w - 9 * u, 8 * u + 5 * u + 2 * padC);
            paint.setColor(0x2EFFFFFF);
            c.drawRoundRect(r, r.height() / 2, r.height() / 2, paint);
            paint.setColor(0xBFFFFFFF);
            float x = r.left + padC;
            for (int i = 0; i < n; i++) {
                RectF it = new RectF(x, r.top + padC, x + itemW, r.bottom - padC);
                c.drawRoundRect(it, it.height() / 2, it.height() / 2, paint);
                x += itemW + gap;
            }
            if (!tv) {
                paint.setColor(Ui.TEXT);
                paint.setTypeface(Ui.LIGHT);
                paint.setTextSize(16 * u);
                c.drawText(DateFormat.format("H:mm", new Date()).toString(), 10 * u, 24 * u, paint);
            }

            String tiles = cfg.get(Config.TILES);
            int cols;
            if (tv) {
                try {
                    cols = Integer.parseInt(cfg.get(Config.COLUMNS));
                } catch (NumberFormatException e) {
                    cols = 5;
                }
            } else {
                cols = "small".equals(tiles) ? 7 : "large".equals(tiles) ? 4 : 6;
            }
            float size = "small".equals(tiles) ? 0.88f : "large".equals(tiles) ? 1.1f : 1f;
            float side = 14 * u, tg = 4 * u;
            float tw = Math.min((w - 2 * side - (cols - 1) * tg) / cols, (w - 2 * side - (cols - 1) * tg) / cols * size);
            float th = tw * 9f / 16f;
            float rowW = cols * tw + (cols - 1) * tg;
            float left = (w - rowW) / 2f;
            float top;
            if (tv) {
                boolean peek = "on".equals(cfg.get(Config.PEEK));
                top = peek ? h - th - 22 * u : h * 0.62f;
                paint.setColor(0x1FFFFFFF);
                r.set(left - 5 * u, top - 5 * u, left + rowW + 5 * u, top + th + 5 * u);
                c.drawRoundRect(r, 8 * u, 8 * u, paint);
                tiles(c, cols, left, top, tw, th, tg, 255);
                if (peek) tiles(c, cols, left, top + th + 12 * u, tw, th, tg, 120);
            } else {
                top = "bottom".equals(cfg.get(Config.POSITION)) ? h - 2 * th - tg - 12 * u : 44 * u;
                tiles(c, cols, left, top, tw, th, tg, 255);
                tiles(c, cols, left, top + th + 6 * u, tw, th, tg, 200);
            }
            // The focus ring on the first app, in the accent colour.
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.4f * u);
            paint.setColor(cfg.accent());
            r.set(left - 1.6f * u, top - 1.6f * u, left + tw + 1.6f * u, top + th + 1.6f * u);
            c.drawRoundRect(r, 3.5f * u, 3.5f * u, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private void tiles(Canvas c, int cols, float left, float top, float tw, float th, float tg, int alpha) {
            boolean glow = "afterglow".equals(cfg.get(Config.TILE_STYLE));
            float u = getWidth() / 240f;
            for (int i = 0; i < cols; i++) {
                float x = left + i * (tw + tg);
                r.set(x, top, x + tw, top + th);
                int col = palette[i % palette.length];
                paint.setColor(col);
                paint.setAlpha(glow ? alpha * 140 / 255 : alpha);
                c.drawRoundRect(r, 2.5f * u, 2.5f * u, paint);
                if (glow) {
                    paint.setColor(0xFFFFFFFF);
                    paint.setAlpha(alpha * 220 / 255);
                    r.set(x + 2 * u, top + 2 * u, x + 2 * u + th * 0.32f, top + 2 * u + th * 0.32f);
                    c.drawRoundRect(r, 1.2f * u, 1.2f * u, paint);
                }
            }
            paint.setAlpha(255);
        }
    }
}
