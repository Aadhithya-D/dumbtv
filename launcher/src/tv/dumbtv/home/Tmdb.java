package tv.dumbtv.home;

import org.json.JSONArray;
import org.json.JSONObject;

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
import java.util.regex.Pattern;

/**
 * Pictures for titles whose app gives none (Hotstar, Sun NXT, Prime): a search on TMDB by
 * title, keeping the wide backdrop for the shelf. Needs the person's own free TMDB key in
 * {@code tmdb.key} next to shelf.json; without it nothing is looked up.
 *
 * <p>It calls api.tmdb.org, not api.themoviedb.org: some Indian providers block the latter.
 * Answers, found or not, are kept in {@code tmdb-cache.json} so a title is asked about once.
 * This product uses the TMDB API but is not endorsed or certified by TMDB.
 */
final class Tmdb {
    private static final String API = "https://api.tmdb.org/3/search/multi";
    private static final String IMAGES = "https://image.tmdb.org/t/p/w1280";
    /** "S1 E1", "S01E02", "Episode 4", "Ep 3": episode markers, not part of a show's name. */
    private static final Pattern EPISODE = Pattern.compile(
            "(?i)\\b(s\\d{1,2}\\s*e\\d{1,3}|season\\s*\\d+|episode\\s*\\d+|ep\\.?\\s*\\d+)\\b.*$");
    private static JSONObject cache;

    private Tmdb() {}

    /** The backdrop URL for {@code title}, or null; looks it up at most once. Not on the UI thread. */
    static synchronized String backdrop(File dir, String title) {
        if (dir == null || title == null) return null;
        String key = key(dir);
        if (key == null) return null;
        String query = clean(title);
        if (query.length() < 2) return null;
        JSONObject c = cache(dir);
        String id = query.toLowerCase(Locale.ROOT);
        if (c.has(id)) {
            String v = c.optString(id, "");
            return v.isEmpty() ? null : v;
        }
        String found = search(key, query);
        // Offline or blocked: ask again next time rather than remembering "nothing".
        if (found == null) return null;
        try {
            c.put(id, found);
            save(dir, c);
        } catch (Exception ignored) {
            // the answer is still used this time
        }
        return found.isEmpty() ? null : found;
    }

    /** The show's name without episode markers: "S1 E1 Rat Race" has no show name left. */
    static String clean(String title) {
        String t = EPISODE.matcher(title.trim()).replaceAll("").trim();
        return t.replaceAll("[\\s\\-|:·•]+$", "").trim();
    }

    /** "" when TMDB has no match with a backdrop; null when TMDB could not be asked. */
    private static String search(String key, String query) {
        HttpURLConnection http = null;
        try {
            String url = API + "?include_adult=false&language=en-US&page=1&api_key=" + key
                    + "&query=" + URLEncoder.encode(query, "UTF-8");
            http = (HttpURLConnection) new URL(url).openConnection();
            http.setConnectTimeout(6000);
            http.setReadTimeout(8000);
            if (http.getResponseCode() != 200) return null;
            JSONArray results;
            try (InputStream in = http.getInputStream()) {
                results = new JSONObject(read(in)).optJSONArray("results");
            }
            if (results == null) return "";
            String want = query.toLowerCase(Locale.ROOT);
            String first = "";
            for (int i = 0; i < results.length(); i++) {
                JSONObject r = results.getJSONObject(i);
                String type = r.optString("media_type");
                if (!"tv".equals(type) && !"movie".equals(type)) continue;
                String backdrop = r.optString("backdrop_path", "");
                if (backdrop.isEmpty() || "null".equals(backdrop)) continue;
                String name = r.optString("tv".equals(type) ? "name" : "title").toLowerCase(Locale.ROOT);
                // An exact name beats TMDB's popularity order.
                if (name.equals(want)) return IMAGES + backdrop;
                if (first.isEmpty()) first = IMAGES + backdrop;
            }
            return first;
        } catch (Exception e) {
            return null;
        } finally {
            if (http != null) http.disconnect();
        }
    }

    private static String key(File dir) {
        File f = new File(dir, "tmdb.key");
        if (!f.isFile()) return null;
        try (InputStream in = new FileInputStream(f)) {
            String k = read(in).trim();
            return k.matches("[0-9a-f]{32}") ? k : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONObject cache(File dir) {
        if (cache != null) return cache;
        File f = new File(dir, "tmdb-cache.json");
        try (InputStream in = new FileInputStream(f)) {
            cache = new JSONObject(read(in));
        } catch (Exception e) {
            cache = new JSONObject();
        }
        return cache;
    }

    private static void save(File dir, JSONObject c) throws Exception {
        File part = new File(dir, "tmdb-cache.json.part");
        try (OutputStream out = new FileOutputStream(part)) {
            out.write(c.toString().getBytes(StandardCharsets.UTF_8));
        }
        part.renameTo(new File(dir, "tmdb-cache.json"));
    }

    private static String read(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }
}
