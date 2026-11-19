package tv.dumbtv.home;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Sun NXT says only the title and length of what it plays. Its own public search
 * (api.sunnxt.com, no account) turns that into the content id, which opens the title straight
 * in its player where it left off (the link its app takes from Google TV's Continue Watching:
 * {@code sunnxt://deeplink?source=GTV_CW&contentId=…}), and its own 16:9 cover. Same-name titles
 * are told apart by length. Answers are kept in {@code sunnxt-cache.json}.
 */
final class SunNxt {
    static final String PKG = "com.suntv.sunnxt";
    private static final String SEARCH = "https://api.sunnxt.com/content/v3/search/?level=devicemax&query=";
    private static JSONObject cache;

    private SunNxt() {}

    /** Gives {@code e} its link and Sun NXT's own picture when the title is found. Network. */
    static synchronized void enrich(File dir, Shelf.Entry e) {
        if (dir == null || e == null || !PKG.equals(e.pkg) || e.title == null) return;
        String key = e.title.toLowerCase(Locale.ROOT) + "|" + Math.round(e.duration / 60_000.0);
        JSONObject c = cache(dir);
        JSONObject hit = c.optJSONObject(key);
        if (hit == null && !c.has(key)) {
            hit = search(e.title, e.duration);
            if (hit == null && !searched) return;   // offline: ask again next time
            try {
                c.put(key, hit == null ? JSONObject.NULL : hit);
                save(dir, c);
            } catch (Exception ignored) {
                // still used this time
            }
        }
        if (hit == null) return;
        e.link = "sunnxt://deeplink?source=GTV_CW&contentId=" + hit.optString("id");
        String img = hit.optString("image", "");
        if (!img.isEmpty()) e.image = img;
    }

    /** Whether the last search reached Sun NXT, so "not found" can be remembered. */
    private static boolean searched;

    private static JSONObject search(String title, long durationMs) {
        searched = false;
        HttpURLConnection http = null;
        try {
            http = (HttpURLConnection) new URL(SEARCH + URLEncoder.encode(title, "UTF-8")).openConnection();
            http.setConnectTimeout(6000);
            http.setReadTimeout(10000);
            http.setRequestProperty("User-Agent", "okhttp/4.12.0");
            if (http.getResponseCode() != 200) return null;
            JSONArray results;
            try (InputStream in = http.getInputStream()) {
                results = new JSONObject(read(in)).optJSONArray("results");
            }
            searched = true;
            if (results == null) return null;
            JSONObject best = null;
            long bestGap = Long.MAX_VALUE;
            for (int i = 0; i < results.length(); i++) {
                JSONObject r = results.getJSONObject(i);
                if (!title.equalsIgnoreCase(r.optString("title").trim())) continue;
                long len = length(r.optJSONObject("content"));
                long gap = durationMs > 0 && len > 0 ? Math.abs(len - durationMs) : Long.MAX_VALUE / 2;
                if (best == null || gap < bestGap) {
                    best = r;
                    bestGap = gap;
                }
            }
            // A known length that is off by more than five minutes is another title of that name.
            if (best == null || durationMs > 0 && bestGap != Long.MAX_VALUE / 2 && bestGap > 5 * 60_000) return null;
            return new JSONObject().put("id", best.optString("_id")).put("image", cover(best));
        } catch (Exception ex) {
            return null;
        } finally {
            if (http != null) http.disconnect();
        }
    }

    /** "2:10:00" in ms; 0 when missing. */
    private static long length(JSONObject content) {
        if (content == null) return 0;
        String[] p = content.optString("duration", "").split(":");
        try {
            long ms = 0;
            for (String s : p) ms = ms * 60 + Long.parseLong(s.trim());
            return p.length >= 2 ? ms * 1000 : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The 16:9 cover at 1280x720, or the closest to it. */
    private static String cover(JSONObject r) {
        JSONObject images = r.optJSONObject("images");
        JSONArray values = images == null ? null : images.optJSONArray("values");
        if (values == null) return "";
        String best = "";
        int bestW = 0;
        for (int i = 0; i < values.length(); i++) {
            JSONObject v = values.optJSONObject(i);
            if (v == null || !"coverposter".equals(v.optString("type"))) continue;
            String[] wh = v.optString("resolution").split("x");
            int w;
            try {
                w = Integer.parseInt(wh[0]);
            } catch (Exception e) {
                continue;
            }
            if (w == 1280) return v.optString("link");
            if (w <= 1920 && w > bestW) {
                best = v.optString("link");
                bestW = w;
            }
        }
        return best;
    }

    private static JSONObject cache(File dir) {
        if (cache != null) return cache;
        try (InputStream in = new FileInputStream(new File(dir, "sunnxt-cache.json"))) {
            cache = new JSONObject(read(in));
        } catch (Exception e) {
            cache = new JSONObject();
        }
        return cache;
    }

    private static void save(File dir, JSONObject c) throws Exception {
        File part = new File(dir, "sunnxt-cache.json.part");
        try (OutputStream out = new FileOutputStream(part)) {
            out.write(c.toString().getBytes(StandardCharsets.UTF_8));
        }
        part.renameTo(new File(dir, "sunnxt-cache.json"));
    }

    private static String read(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }
}
