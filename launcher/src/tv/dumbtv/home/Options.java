package tv.dumbtv.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.StateListDrawable;
import android.media.tv.TvInputManager;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Apple TV-style two-pane Options screen: 5 categories on the left, settings on the right.
 * Left/Right or OK cycles values; Back returns from the settings pane to the category sidebar,
 * and from the sidebar back to Home.
 */
public final class Options extends Activity {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private final List<Runnable> refreshers = new ArrayList<>();
    private final List<TextView> navItems = new ArrayList<>();
    private final List<View> panes = new ArrayList<>();
    private final List<LinearLayout> paneLists = new ArrayList<>();
    private Config cfg;
    private int activeTab;
    private TextView paneTitle, paneDesc;

    private static final String[] TAB_TITLES = {
        "Home & Layout",
        "Wallpaper & Look",
        "Apps & Inputs",
        "Top Shelf & Pins",
        "System",
    };
    private static final String[] TAB_DESCS = {
        "Grid structure, tile sizing, home style and top-bar controls.",
        "Background photography, rotation speed, dimming, icons and accent.",
        "Choose which apps and HDMI inputs appear on your home screen.",
        "Continue Watching cards, phone pinning and the library banner.",
        "Clock format, memory meter, child lock, backup and Amazon home.",
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.initFonts(this);
        cfg = Config.get(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(0xFF0B0C10);

        // --- Left Category Sidebar ---
        LinearLayout sidebar = new LinearLayout(this);
        sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setBackgroundColor(0xFF101218);
        sidebar.setPadding(dp(22), dp(36), dp(18), dp(24));

        TextView heading = Ui.text(this, 26, Ui.TEXT, Ui.SEMIBOLD);
        heading.setText(R.string.options);
        heading.setLetterSpacing(-0.02f);
        heading.setPadding(dp(12), 0, 0, dp(22));
        sidebar.addView(heading);

        LinearLayout navList = new LinearLayout(this);
        navList.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < TAB_TITLES.length; i++) {
            final int idx = i;
            TextView nav = Ui.text(this, 15, Ui.MUTED, Ui.MEDIUM);
            nav.setText(TAB_TITLES[i]);
            nav.setFocusable(true);
            nav.setClickable(true);
            nav.setGravity(Gravity.CENTER_VERTICAL);
            nav.setPadding(dp(14), dp(12), dp(14), dp(12));
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(MATCH, WRAP);
            nlp.bottomMargin = dp(4);
            nav.setLayoutParams(nlp);
            nav.setOnFocusChangeListener((v, has) -> {
                if (has) selectTab(idx);
                styleNav();
            });
            nav.setOnClickListener(v -> {
                selectTab(idx);
                focusPane(idx);
            });
            nav.setOnKeyListener((v, code, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    focusPane(idx);
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) return true;
                return false;
            });
            navItems.add(nav);
            navList.addView(nav);
        }
        sidebar.addView(navList, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        TextView foot = Ui.text(this, 12, Ui.FAINT, Ui.REGULAR);
        foot.setText("dumbtv home");
        foot.setPadding(dp(12), dp(12), 0, 0);
        sidebar.addView(foot);

        root.addView(sidebar, new LinearLayout.LayoutParams(dp(248), MATCH));

        View divider = new View(this);
        divider.setBackgroundColor(0x16FFFFFF);
        root.addView(divider, new LinearLayout.LayoutParams(dp(1), MATCH));

        // --- Right Settings Pane ---
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(dp(40), dp(36), dp(48), dp(22));

        paneTitle = Ui.text(this, 24, Ui.TEXT, Ui.SEMIBOLD);
        paneTitle.setLetterSpacing(-0.02f);
        right.addView(paneTitle);

        paneDesc = Ui.text(this, 13, Ui.MUTED, Ui.REGULAR);
        paneDesc.setPadding(0, dp(4), 0, dp(18));
        right.addView(paneDesc);

        FrameLayout content = new FrameLayout(this);
        for (int i = 0; i < TAB_TITLES.length; i++) {
            ScrollView sv = new ScrollView(this);
            sv.setFocusable(false);
            sv.setVerticalScrollBarEnabled(false);
            sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
            LinearLayout list = new LinearLayout(this);
            list.setOrientation(LinearLayout.VERTICAL);
            list.setPadding(0, dp(2), 0, dp(16));
            sv.addView(list, new FrameLayout.LayoutParams(MATCH, WRAP));
            sv.setVisibility(i == 0 ? View.VISIBLE : View.GONE);
            panes.add(sv);
            paneLists.add(list);
            content.addView(sv, new FrameLayout.LayoutParams(MATCH, MATCH));
        }
        populatePanes();
        right.addView(content, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        View hintLine = new View(this);
        hintLine.setBackgroundColor(0x14FFFFFF);
        right.addView(hintLine, new LinearLayout.LayoutParams(MATCH, dp(1)));

        LinearLayout hints = new LinearLayout(this);
        hints.setOrientation(LinearLayout.HORIZONTAL);
        hints.setPadding(dp(2), dp(10), dp(2), 0);
        TextView hintLeft = Ui.text(this, 12, Ui.FAINT, Ui.REGULAR);
        hintLeft.setText("◀ ▶  Change value     OK  Select");
        TextView hintRight = Ui.text(this, 12, Ui.FAINT, Ui.REGULAR);
        hintRight.setGravity(Gravity.END);
        hintRight.setText("BACK  Categories / Home");
        hints.addView(hintLeft, new LinearLayout.LayoutParams(0, WRAP, 1f));
        hints.addView(hintRight, new LinearLayout.LayoutParams(WRAP, WRAP));
        right.addView(hints, new LinearLayout.LayoutParams(MATCH, WRAP));

        root.addView(right, new LinearLayout.LayoutParams(0, MATCH, 1f));
        setContentView(root);
        selectTab(0);
        refresh();
        navItems.get(0).requestFocus();
    }

    private void populatePanes() {
        // 0. Home & Layout
        LinearLayout home = paneLists.get(0);
        home.addView(choice(Config.STYLE));
        home.addView(choice(Config.LAYOUT));
        home.addView(choice(Config.COLUMNS));
        home.addView(choice(Config.TILES));
        home.addView(choice(Config.PEEK));
        home.addView(choice(Config.POSITION));
        home.addView(choice(Config.TILE_STYLE));
        home.addView(choice(Config.CHIPS));

        // 1. Wallpaper & Look
        LinearLayout wall = paneLists.get(1);
        wall.addView(choice(Config.BACKGROUND));
        wall.addView(choice(Config.ROTATE));
        wall.addView(choice(Config.DIM));
        wall.addView(action(getString(R.string.next_wallpaper), () -> {
            cfg.nextWallpaper();
            refresh();
        }));
        LinearLayout packRow = new LinearLayout(this);
        packRow.setOrientation(LinearLayout.VERTICAL);
        wall.addView(packRow);
        // Finding the packs opens every theme app's resources: keep it off the UI thread.
        new Thread(() -> {
            Config.Choice packs = IconPack.choice(IconPack.installed(getPackageManager()),
                    getString(R.string.icon_pack_off), getString(R.string.icon_pack_none));
            runOnUiThread(() -> {
                if (!isDestroyed()) {
                    packRow.addView(choice(packs));
                    refresh();
                }
            });
        }, "icon-packs").start();
        wall.addView(choice(Config.ACCENT));

        // 2. Apps & Inputs
        LinearLayout apps = paneLists.get(2);
        apps.addView(action(getString(R.string.apps_on_home), () -> pick(Catalog.APPS)));
        apps.addView(choice(Config.ORDER));
        apps.addView(choice(Config.LABELS));
        apps.addView(choice(Config.ALL_APPS));
        apps.addView(choice(Config.INPUTS));
        apps.addView(action(getString(R.string.inputs_on_home), () -> pick(Catalog.INPUTS)));
        apps.addView(choice(Config.SWITCH));

        // 3. Top Shelf & Pins
        LinearLayout shelf = paneLists.get(3);
        shelf.addView(choice(Config.SHELF));
        shelf.addView(choice(Config.LIBRARY));
        TextView shelfNote = Ui.text(this, 13, Ui.FAINT, Ui.REGULAR);
        shelfNote.setLineSpacing(0, 1.45f);
        shelfNote.setPadding(dp(14), dp(14), dp(14), 0);
        shelfNote.setText("When Top Shelf is on in Apple TV style, pin shows from your phone at http://<tv-ip>:8080.");
        shelf.addView(shelfNote);

        // 4. System
        LinearLayout sys = paneLists.get(4);
        sys.addView(choice(Config.CLOCK));
        sys.addView(choice(Config.MEMORY));
        sys.addView(choice(Config.LOCK));
        sys.addView(action(getString(R.string.export_settings), this::export));
        sys.addView(action(getString(R.string.reset_layout), this::reset));
        sys.addView(action(getString(R.string.open_amazon_home), () -> {
            Guard.amazon(this);
            finish();
        }));
        TextView about = Ui.text(this, 12, Ui.FAINT, Ui.REGULAR);
        about.setLineSpacing(0, 1.4f);
        about.setPadding(dp(14), dp(18), dp(14), 0);
        about.setText(getString(R.string.about, cfg.dir + "/" + Config.FILE, Config.WALLPAPERS,
                "/sdcard/Android/media/" + getPackageName() + "/", Library.FILE, Library.DIR));
        sys.addView(about);
    }

    private void selectTab(int idx) {
        activeTab = idx;
        for (int i = 0; i < panes.size(); i++) {
            panes.get(i).setVisibility(i == idx ? View.VISIBLE : View.GONE);
        }
        paneTitle.setText(TAB_TITLES[idx]);
        paneDesc.setText(TAB_DESCS[idx]);
        styleNav();
    }

    private void focusPane(int idx) {
        LinearLayout list = paneLists.get(idx);
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            if (child.isFocusable()) {
                child.requestFocus();
                return;
            }
            if (child instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) child;
                if (g.getChildCount() > 0 && g.getChildAt(0).isFocusable()) {
                    g.getChildAt(0).requestFocus();
                    return;
                }
            }
        }
    }

    private boolean inSidebar() {
        View f = getCurrentFocus();
        return f != null && navItems.contains(f);
    }

    @Override
    public void onBackPressed() {
        if (!inSidebar()) {
            navItems.get(activeTab).requestFocus();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        cfg.refresh();
        refresh();
    }

    private void refresh() {
        for (Runnable r : refreshers) r.run();
        styleNav();
    }

    private void styleNav() {
        int accent = cfg.accent();
        int ink = Ui.ink(accent);
        for (int i = 0; i < navItems.size(); i++) {
            TextView nav = navItems.get(i);
            boolean focused = nav.isFocused();
            boolean selected = i == activeTab;
            if (focused) {
                nav.setBackground(Ui.round(accent, dp(10)));
                nav.setTextColor(ink);
                nav.setTypeface(Ui.SEMIBOLD);
            } else if (selected) {
                nav.setBackground(Ui.round(0x1AFFFFFF, dp(10)));
                nav.setTextColor(Ui.TEXT);
                nav.setTypeface(Ui.SEMIBOLD);
            } else {
                nav.setBackground(null);
                nav.setTextColor(Ui.MUTED);
                nav.setTypeface(Ui.MEDIUM);
            }
        }
    }

    private View choice(Config.Choice c) {
        LinearLayout row = row();
        TextView name = label(Text.title(this, c));
        TextView value = Ui.text(this, 15, Ui.MUTED, Ui.MEDIUM);
        value.setGravity(Gravity.END);
        row.addView(name, new LinearLayout.LayoutParams(0, WRAP, 1f));
        row.addView(value, new LinearLayout.LayoutParams(WRAP, WRAP));
        row.setOnClickListener(v -> step(c, 1));
        row.setOnKeyListener((v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) return step(c, -1);
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) return step(c, 1);
            return false;
        });
        Runnable show = () -> {
            boolean focused = row.isFocused();
            int ink = Ui.ink(cfg.accent());
            String text = Text.name(this, c, cfg.get(c));
            if (c == Config.ACCENT) {
                SpannableString s = new SpannableString("●  " + text);
                s.setSpan(new ForegroundColorSpan(focused ? ink : cfg.accent()), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                value.setText(focused ? "‹  " : "");
                value.append(s);
                if (focused) value.append("  ›");
            } else {
                value.setText(focused ? "‹  " + text + "  ›" : text);
            }
            name.setTextColor(focused ? ink : Ui.TEXT);
            name.setTypeface(focused ? Ui.SEMIBOLD : Ui.MEDIUM);
            value.setTextColor(focused ? ink : Ui.MUTED);
            value.setTypeface(focused ? Ui.SEMIBOLD : Ui.MEDIUM);
            boolean off = (c == Config.DIM || c == Config.ROTATE)
                    && !"image".equals(cfg.get(Config.BACKGROUND))
                    && !"adaptive-tint".equals(cfg.get(Config.BACKGROUND))
                    || c == Config.SWITCH && "off".equals(cfg.get(Config.INPUTS))
                    || c == Config.ALL_APPS && "on".equals(cfg.get(Config.LOCK));
            row.setAlpha(off ? 0.42f : 1f);
        };
        row.setOnFocusChangeListener((v, has) -> {
            show.run();
            styleNav();
        });
        refreshers.add(show);
        refreshers.add(() -> style(row));
        return row;
    }

    private boolean step(Config.Choice c, int by) {
        int n = c.values.length;
        // Nothing to switch to, as Icon pack with none installed: keep what is saved, so a pack
        // that comes back is still the chosen one.
        if (n < 2) return true;
        int i = Arrays.asList(c.values).indexOf(cfg.get(c));
        cfg.set(c.key, c.values[((i + by) % n + n) % n]);
        refresh();
        if (c == Config.LOCK && "on".equals(cfg.get(c))) {
            Toast.makeText(this, getString(R.string.lock_on, (int) (Home.UNLOCK_MS / 1000)),
                    Toast.LENGTH_LONG).show();
        }
        return true;
    }

    private View action(String text, Runnable run) {
        LinearLayout row = row();
        TextView name = label(text);
        TextView chevron = Ui.text(this, 16, Ui.MUTED, Ui.MEDIUM);
        chevron.setText("›");
        row.addView(name, new LinearLayout.LayoutParams(0, WRAP, 1f));
        row.addView(chevron);
        row.setOnClickListener(v -> run.run());
        row.setOnKeyListener((v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                navItems.get(activeTab).requestFocus();
                return true;
            }
            return false;
        });
        Runnable show = () -> {
            boolean focused = row.isFocused();
            int ink = Ui.ink(cfg.accent());
            name.setTextColor(focused ? ink : Ui.TEXT);
            name.setTypeface(focused ? Ui.SEMIBOLD : Ui.MEDIUM);
            chevron.setTextColor(focused ? ink : Ui.MUTED);
        };
        row.setOnFocusChangeListener((v, has) -> {
            show.run();
            styleNav();
        });
        refreshers.add(show);
        refreshers.add(() -> style(row));
        return row;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setPadding(dp(18), dp(14), dp(18), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.bottomMargin = dp(6);
        row.setLayoutParams(lp);
        return row;
    }

    private void style(View row) {
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_focused}, Ui.round(cfg.accent(), dp(12)));
        bg.addState(new int[0], Ui.roundStroke(0x0AFFFFFF, 0x0FFFFFFF, dp(1), dp(12)));
        row.setBackground(bg);
    }

    private TextView label(String text) {
        TextView v = Ui.text(this, 15, Ui.TEXT, Ui.MEDIUM);
        v.setText(text);
        return v;
    }

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
        java.io.File copy = cfg.export();
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

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
