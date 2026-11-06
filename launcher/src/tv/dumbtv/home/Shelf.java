package tv.dumbtv.home;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Top Shelf: what each app says you are watching, and the titles you pinned.
 *
 * Apps built with Amazon's Fire TV Integration SDK hand their Continue Watching list to the
 * SDK, which uploads it to Amazon and also writes it to the system log under the tag
 * {@code FTVIntegrationSDK}. This reads that log (READ_LOGS, granted over adb) and keeps the
 * latest list per app in {@code shelf.json}, so the shelf survives a reboot. Pins are copies of
 * entries the person chose to keep, with their picture saved next to them.
 */
final class Shelf {
    static final class Entry {
        String pkg, id, title, subtitle = "", description = "", link, image;
        long progress, duration, watched;

        JSONObject json() throws org.json.JSONException {
            return new JSONObject().put("pkg", pkg).put("id", id).put("title", title)
                    .put("subtitle", subtitle).put("description", description).put("link", link)
                    .put("image", image == null ? "" : image).put("progress", progress)
                    .put("duration", duration).put("watched", watched);
        }

        static Entry from(JSONObject o) {
            Entry e = new Entry();
            e.pkg = o.optString("pkg");
            e.id = o.optString("id");
            e.title = o.optString("title");
            e.subtitle = o.optString("subtitle");
            e.description = o.optString("description");
            e.link = o.optString("link");
            e.image = o.optString("image");
            if (e.image.isEmpty()) e.image = null;
            e.progress = o.optLong("progress");
            e.duration = o.optLong("duration");
            e.watched = o.optLong("watched");
            return e;
        }
    }

    interface Listener {
        void shelfChanged(String pkg);
    }

    private static final String TAG = "FTVIntegrationSDK";
    private static final Pattern INVOKED = Pattern.compile("function: (\\w+) invoked by package: ([\\w.]+)");
    private static final Pattern ITEM = Pattern.compile("AmazonContinueWatchingItem\\((.*)\\)\\)?\\s*$");
    private static final Pattern META = Pattern.compile("AmazonContinueWatchingItemMetadata\\((.*?)(?:\\)\\)?)?\\s*$");
    private static final String[] KEYS = {"opaqueId", "title", "description", "shortDescription", "deeplinkUri",
        "locale", "tileImage", "heroImage", "maturityRatingSystem", "maturityRating", "statusFlags",
        "releaseYear", "progressMs", "durationMs", "seasonNumber", "episodeNumber", "episodeTitle", "actors",
        "directors", "isOriginal", "removeIntentUri", "lastWatchedTimestampMs", "genres", "contentType",
        "isNextEpisodeInSeries", "playbackUri", "launchUri"};
    private static final Pattern KEY;

    static {
        StringBuilder alt = new StringBuilder();
        for (String k : KEYS) alt.append(alt.length() == 0 ? "" : "|").append(k);
        KEY = Pattern.compile("(?:^|, )(" + alt + ")=");
    }

    private static final Map<String, List<Entry>> watching = new HashMap<>();
    private static final Map<String, Map<String, long[]>> watchedAt = new HashMap<>();
    private static final List<Entry> pins = new ArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Listener listener;
    private static File dir;
    private static boolean started;

    private Shelf() {}

    /** Loads the saved shelf and starts following the log; once per process. */
    static synchronized void start(Context c) {
        if (started) return;
        started = true;
        dir = c.getFilesDir();
        load();
        Thread t = new Thread(Shelf::follow, "shelf-log");
        t.setDaemon(true);
        t.start();
    }

    static synchronized void setListener(Listener l) {
        listener = l;
    }

    /** Pins for {@code pkg} first, newest first, then what it says you are watching; at most {@code max}. */
    static synchronized List<Entry> forApp(String pkg, int max) {
        List<Entry> out = new ArrayList<>();
        Set<String> titles = new HashSet<>();
        for (int i = pins.size() - 1; i >= 0 && out.size() < max; i--) {
            Entry e = pins.get(i);
            if (e.pkg.equals(pkg)) {
                out.add(e);
                titles.add(e.title);
            }
        }
        List<Entry> list = watching.get(pkg);
        if (list != null) {
            for (Entry e : list) {
                if (out.size() >= max) break;
                if (!titles.contains(e.title)) out.add(e);
            }
        }
        return out;
    }

    static synchronized boolean pinned(Entry e) {
        for (Entry p : pins) if (same(p, e)) return true;
        return false;
    }

    /** Apps give a title a new opaque id each time, so a pin is matched by app and title. */
    private static boolean same(Entry a, Entry b) {
        return a.pkg.equals(b.pkg) && a.title.equals(b.title);
    }

    static synchronized void pin(Entry e, boolean on) {
        for (int i = pins.size() - 1; i >= 0; i--) {
            Entry p = pins.get(i);
            if (same(p, e)) pins.remove(i);
        }
        if (on) pins.add(e);
        save();
        changed(e.pkg);
    }

    /** The saved picture for {@code e}, downloading it first if needed; null when offline. Not on the UI thread. */
    static File image(Entry e) {
        if (e.image == null || dir == null) return null;
        File cache = new File(dir, "shelf-img");
        File f = new File(cache, Integer.toHexString(e.image.hashCode()) + ".jpg");
        if (f.length() > 0) return f;
        cache.mkdirs();
        HttpURLConnection http = null;
        try {
            http = (HttpURLConnection) new URL(e.image).openConnection();
            http.setConnectTimeout(5000);
            http.setReadTimeout(8000);
            File part = new File(cache, f.getName() + ".part");
            try (InputStream in = http.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            }
            return part.renameTo(f) ? f : null;
        } catch (Exception ex) {
            return null;
        } finally {
            if (http != null) http.disconnect();
        }
    }

    // ---- the log ----

    private static void follow() {
        while (true) {
            Process p = null;
            try {
                p = new ProcessBuilder("logcat", "-v", "raw", "-s", TAG + ":I").redirectErrorStream(true).start();
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                String fn = null, pkg = null;
                List<Entry> meta = null;
                for (String line; (line = r.readLine()) != null; ) {
                    Matcher m = INVOKED.matcher(line);
                    if (m.find()) {
                        fn = m.group(1);
                        pkg = m.group(2);
                        meta = "setContinueWatchingItemMetadata".equals(fn) ? new ArrayList<>() : null;
                        if ("setContinueWatchingItems".equals(fn)) {
                            synchronized (Shelf.class) {
                                watchedAt.put(pkg, new HashMap<>());
                            }
                        }
                        continue;
                    }
                    if (pkg == null) continue;
                    Matcher meta1 = META.matcher(line);
                    if (meta1.find() && meta != null) {
                        Entry e = parse(pkg, fields(meta1.group(1)));
                        // Android cuts a log message at about 4 KB, so a long list often never
                        // reaches its closing "))": keep each title as soon as it is read.
                        if (e != null) {
                            meta.add(e);
                            commit(pkg, new ArrayList<>(meta));
                        }
                        if (line.trim().endsWith("))")) meta = null;
                        continue;
                    }
                    Matcher item = ITEM.matcher(line);
                    if (item.find() && "setContinueWatchingItems".equals(fn)) {
                        Map<String, String> f = fields(item.group(1));
                        synchronized (Shelf.class) {
                            Map<String, long[]> at = watchedAt.get(pkg);
                            if (at != null && f.get("opaqueId") != null) {
                                at.put(f.get("opaqueId"), new long[] {number(f.get("lastWatchedTimestampMs")),
                                    number(f.get("progressMs")), number(f.get("durationMs"))});
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
                // no READ_LOGS yet, or logcat went away: try again later
            } finally {
                if (p != null) p.destroy();
            }
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static Map<String, String> fields(String body) {
        Map<String, String> out = new HashMap<>();
        Matcher m = KEY.matcher(body);
        String key = null;
        int start = 0;
        while (m.find()) {
            if (key != null) out.put(key, body.substring(start, m.start()));
            key = m.group(1);
            start = m.end();
        }
        if (key != null) out.put(key, body.substring(start));
        return out;
    }

    private static Entry parse(String pkg, Map<String, String> f) {
        String id = f.get("opaqueId"), title = f.get("title"), link = f.get("deeplinkUri");
        if (id == null || title == null || link == null || "null".equals(link)) return null;
        Entry e = new Entry();
        e.pkg = pkg;
        e.id = id;
        e.title = title;
        e.link = link;
        String desc = f.get("description");
        e.description = desc == null || "null".equals(desc) ? "" : desc;
        e.image = imageUri(f.get("tileImage"));
        if (e.image == null) e.image = imageUri(f.get("heroImage"));
        e.progress = number(f.get("progressMs"));
        e.duration = number(f.get("durationMs"));
        long season = number(f.get("seasonNumber")), episode = number(f.get("episodeNumber"));
        String ep = f.get("episodeTitle");
        StringBuilder sub = new StringBuilder();
        if (season > 0 && episode > 0) sub.append("S").append(season).append(" E").append(episode);
        if (ep != null && !"null".equals(ep) && !ep.isEmpty()) sub.append(sub.length() > 0 ? " · " : "").append(ep);
        e.subtitle = sub.toString();
        return e;
    }

    private static String imageUri(String image) {
        if (image == null) return null;
        Matcher m = Pattern.compile("imageUri=([^,)\\s]+)").matcher(image);
        return m.find() ? m.group(1) : null;
    }

    private static long number(String s) {
        if (s == null) return 0;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void commit(String pkg, List<Entry> meta) {
        if (pkg == null || meta == null || meta.isEmpty()) return;
        synchronized (Shelf.class) {
            Map<String, long[]> at = watchedAt.get(pkg);
            if (at != null) {
                for (Entry e : meta) {
                    long[] w = at.get(e.id);
                    if (w == null) continue;
                    e.watched = w[0];
                    // A title cut off by the log's size limit loses its own progress fields.
                    if (e.duration == 0) {
                        e.progress = w[1];
                        e.duration = w[2];
                    }
                }
            }
            List<Entry> sorted = new ArrayList<>(meta);
            // Most recently watched first; entries the app gave no time for keep their order after.
            Collections.sort(sorted, (a, b) -> Long.compare(b.watched, a.watched));
            watching.put(pkg, sorted);
            save();
        }
        changed(pkg);
    }

    private static void changed(String pkg) {
        main.post(() -> {
            Listener l;
            synchronized (Shelf.class) {
                l = listener;
            }
            if (l != null) l.shelfChanged(pkg);
        });
    }

    // ---- shelf.json ----

    private static void load() {
        File f = new File(dir, "shelf.json");
        if (!f.isFile()) return;
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0;
            for (int n; off < b.length && (n = in.read(b, off, b.length - off)) > 0; ) off += n;
            JSONObject root = new JSONObject(new String(b, 0, off, StandardCharsets.UTF_8));
            JSONObject w = root.optJSONObject("watching");
            if (w != null) {
                for (java.util.Iterator<String> it = w.keys(); it.hasNext(); ) {
                    String pkg = it.next();
                    JSONArray a = w.getJSONArray(pkg);
                    List<Entry> list = new ArrayList<>();
                    for (int i = 0; i < a.length(); i++) list.add(Entry.from(a.getJSONObject(i)));
                    watching.put(pkg, list);
                }
            }
            JSONArray p = root.optJSONArray("pins");
            if (p != null) for (int i = 0; i < p.length(); i++) pins.add(Entry.from(p.getJSONObject(i)));
        } catch (Exception ignored) {
            // a damaged file starts the shelf empty
        }
    }

    private static void save() {
        if (dir == null) return;
        try {
            JSONObject w = new JSONObject();
            for (Map.Entry<String, List<Entry>> e : watching.entrySet()) {
                JSONArray a = new JSONArray();
                for (Entry x : e.getValue()) a.put(x.json());
                w.put(e.getKey(), a);
            }
            JSONArray p = new JSONArray();
            for (Entry x : pins) p.put(x.json());
            File part = new File(dir, "shelf.json.part");
            try (OutputStream out = new FileOutputStream(part)) {
                out.write(new JSONObject().put("watching", w).put("pins", p).toString().getBytes(StandardCharsets.UTF_8));
            }
            part.renameTo(new File(dir, "shelf.json"));
        } catch (Exception ignored) {
            // keep going; the next change writes it again
        }
    }
}
