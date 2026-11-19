package tv.dumbtv.home;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stremio's own Continue Watching, from the person's Stremio account: every library title with
 * a saved position, newest first, with its wide background, the episode, the time left and a
 * {@code stremio://} link that opens that episode. Needs the account's auth key in
 * {@code stremio.auth} next to shelf.json (it is what Stremio's own apps keep after signing
 * in); without it nothing is asked. Read-only: it never changes the library.
 */
final class Stremio {
    static final String PKG = "com.stremio.one";
    private static final String API = "https://api.strem.io/api/datastoreGet";
    private static final long EVERY_MS = 30_000;
    private static long last;

    private Stremio() {}

    /** Fetches the list when it is due and hands it to the shelf. Network: not on the UI thread. */
    static void refresh(File dir, boolean force) {
        if (dir == null) return;
        long now = System.currentTimeMillis();
        synchronized (Stremio.class) {
            if (!force && now - last < EVERY_MS) return;
            last = now;
        }
        String key = key(dir);
        if (key == null) return;
        List<Shelf.Entry> list = fetch(key);
        if (list != null) Shelf.watching(PKG, list);
    }

    private static List<Shelf.Entry> fetch(String key) {
        HttpURLConnection http = null;
        try {
            http = (HttpURLConnection) new URL(API).openConnection();
            http.setConnectTimeout(6000);
            http.setReadTimeout(10000);
            http.setDoOutput(true);
            http.setRequestProperty("Content-Type", "application/json");
            byte[] body = new JSONObject().put("authKey", key).put("collection", "libraryItem")
                    .put("ids", new JSONArray()).put("all", true).toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = http.getOutputStream()) {
                out.write(body);
            }
            if (http.getResponseCode() != 200) return null;
            JSONArray items;
            try (InputStream in = http.getInputStream()) {
                items = new JSONObject(read(in)).optJSONArray("result");
            }
            if (items == null) return null;
            List<Shelf.Entry> out = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                Shelf.Entry e = entry(items.getJSONObject(i));
                if (e != null) out.add(e);
            }
            Collections.sort(out, (a, b) -> Long.compare(b.watched, a.watched));
            return out.size() > 12 ? new ArrayList<>(out.subList(0, 12)) : out;
        } catch (Exception e) {
            return null;
        } finally {
            if (http != null) http.disconnect();
        }
    }

    /** One library title in Continue Watching, or null: removed, never started, or finished. */
    static Shelf.Entry entry(JSONObject it) {
        if (it.optBoolean("removed") && !it.optBoolean("temp")) return null;
        JSONObject st = it.optJSONObject("state");
        if (st == null) return null;
        long offset = st.optLong("timeOffset"), duration = st.optLong("duration");
        if (offset <= 0) return null;
        // Nearly done counts as watched: Stremio leaves those out of Continue Watching too.
        if (duration > 0 && offset > duration * 0.95) return null;
        String id = it.optString("_id"), type = it.optString("type"), name = it.optString("name");
        if (id.isEmpty() || name.isEmpty()) return null;
        String video = st.optString("video_id", "");
        Shelf.Entry e = new Shelf.Entry();
        e.pkg = PKG;
        e.id = id;
        e.title = name;
        e.progress = offset;
        e.duration = duration;
        e.watched = time(st.optString("lastWatched", ""));
        e.subtitle = episode(video);
        e.description = "";
        e.image = id.startsWith("tt")
                ? "https://images.metahub.space/background/medium/" + id + "/img"
                : emptyToNull(it.optString("poster", ""));
        e.link = "stremio:///detail/" + type + "/" + id + "/" + (video.isEmpty() ? id : video);
        return e;
    }

    /** "tt0944947:1:3" is season 1, episode 3. */
    static String episode(String video) {
        String[] p = video.split(":");
        if (p.length == 3) {
            try {
                return "S" + Integer.parseInt(p[1]) + " E" + Integer.parseInt(p[2]);
            } catch (NumberFormatException ignored) {
                // not an episode id
            }
        }
        return "";
    }

    /** Stremio's ISO time ("2026-11-19T10:42:14.000Z") in ms; 0 when missing. */
    private static long time(String iso) {
        if (iso.isEmpty()) return 0;
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.ROOT);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return f.parse(iso.substring(0, Math.min(19, iso.length()))).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static String key(File dir) {
        File f = new File(dir, "stremio.auth");
        if (!f.isFile()) return null;
        try (InputStream in = new FileInputStream(f)) {
            String k = read(in).trim();
            return k.isEmpty() ? null : k;
        } catch (Exception e) {
            return null;
        }
    }

    private static String read(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }
}
