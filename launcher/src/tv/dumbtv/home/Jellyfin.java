package tv.dumbtv.home;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Jellyfin's own Resume list, from the person's server: what they started and did not finish,
 * newest first, with the show's backdrop, the episode, and the time left. Reads
 * {@code jellyfin.json} next to shelf.json ({@code {"server": "http://…:8096", "key": "…",
 * "user": "…"}}, an API key made in the server's dashboard); without it nothing is asked.
 * Read-only. The Jellyfin app takes no link to a title, so a card opens the app.
 */
final class Jellyfin {
    static final String PKG = "org.jellyfin.androidtv";
    private static final long EVERY_MS = 30_000;
    private static long last;

    private Jellyfin() {}

    /** Fetches the Resume list when it is due and hands it to the shelf. Network. */
    static void refresh(File dir, boolean force) {
        if (dir == null) return;
        long now = System.currentTimeMillis();
        synchronized (Jellyfin.class) {
            if (!force && now - last < EVERY_MS) return;
            last = now;
        }
        JSONObject conf = conf(dir);
        if (conf == null) return;
        String server = conf.optString("server").replaceAll("/+$", "");
        String key = conf.optString("key"), user = conf.optString("user");
        if (server.isEmpty() || key.isEmpty() || user.isEmpty()) return;
        List<Shelf.Entry> list = fetch(server, key, user);
        if (list != null) Shelf.watching(PKG, list);
    }

    private static List<Shelf.Entry> fetch(String server, String key, String user) {
        HttpURLConnection http = null;
        try {
            String url = server + "/Users/" + user + "/Items/Resume?Limit=12&MediaTypes=Video"
                    + "&Fields=Overview&EnableUserData=true";
            http = (HttpURLConnection) new URL(url).openConnection();
            http.setConnectTimeout(5000);
            http.setReadTimeout(10000);
            // Jellyfin 10.11 and later refuse the old X-Emby-Token header.
            http.setRequestProperty("Authorization", "MediaBrowser Token=\"" + key + "\"");
            int code = http.getResponseCode();
            if (code != 200) {
                android.util.Log.w("dumbtv", "jellyfin: resume answered " + code);
                return null;
            }
            JSONArray items;
            try (InputStream in = http.getInputStream()) {
                items = new JSONObject(read(in)).optJSONArray("Items");
            }
            if (items == null) return null;
            List<Shelf.Entry> out = new ArrayList<>();
            java.util.Set<String> shows = new java.util.HashSet<>();
            for (int i = 0; i < items.length(); i++) {
                Shelf.Entry e = entry(server, items.getJSONObject(i));
                if (e != null && shows.add(e.title)) out.add(e);
            }
            // Then the next episode of each show being followed, as Apple TV's Up Next does.
            JSONArray next = get(server + "/Shows/NextUp?UserId=" + user + "&Limit=12&EnableUserData=true", key);
            for (int i = 0; next != null && i < next.length() && out.size() < 12; i++) {
                Shelf.Entry e = entry(server, next.getJSONObject(i));
                if (e == null || !shows.add(e.title)) continue;
                e.status = "Up Next";
                out.add(e);
            }
            return out;
        } catch (Exception e) {
            android.util.Log.w("dumbtv", "jellyfin: " + e);
            return null;
        } finally {
            if (http != null) http.disconnect();
        }
    }

    private static JSONArray get(String url, String key) {
        HttpURLConnection http = null;
        try {
            http = (HttpURLConnection) new URL(url).openConnection();
            http.setConnectTimeout(5000);
            http.setReadTimeout(10000);
            http.setRequestProperty("Authorization", "MediaBrowser Token=\"" + key + "\"");
            if (http.getResponseCode() != 200) return null;
            try (InputStream in = http.getInputStream()) {
                return new JSONObject(read(in)).optJSONArray("Items");
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (http != null) http.disconnect();
        }
    }

    static Shelf.Entry entry(String server, JSONObject it) {
        String id = it.optString("Id"), name = it.optString("Name");
        if (id.isEmpty() || name.isEmpty()) return null;
        JSONObject ud = it.optJSONObject("UserData");
        Shelf.Entry e = new Shelf.Entry();
        e.pkg = PKG;
        e.id = id;
        boolean episode = "Episode".equals(it.optString("Type")) && !it.optString("SeriesName").isEmpty();
        e.title = episode ? it.optString("SeriesName") : name;
        StringBuilder sub = new StringBuilder();
        if (episode) {
            int s = it.optInt("ParentIndexNumber", 0), n = it.optInt("IndexNumber", 0);
            if (s > 0 && n > 0) sub.append("S").append(s).append(" E").append(n).append(" · ");
            sub.append(name);
        }
        e.subtitle = sub.toString();
        e.description = "";
        // Ticks are 100 ns.
        e.duration = it.optLong("RunTimeTicks") / 10_000;
        e.progress = ud == null ? 0 : ud.optLong("PlaybackPositionTicks") / 10_000;
        e.watched = ud == null ? 0 : time(ud.optString("LastPlayedDate", ""));
        e.image = image(server, it);
        e.link = null;
        return e;
    }

    /** The show's or film's wide backdrop, else the episode's thumb, else its poster. */
    private static String image(String server, JSONObject it) {
        String base = server + "/Items/";
        String parent = it.optString("ParentBackdropItemId", "");
        JSONArray parentTags = it.optJSONArray("ParentBackdropImageTags");
        if (!parent.isEmpty() && parentTags != null && parentTags.length() > 0) {
            return base + parent + "/Images/Backdrop/0?maxWidth=1280&tag=" + parentTags.optString(0);
        }
        JSONArray own = it.optJSONArray("BackdropImageTags");
        if (own != null && own.length() > 0) {
            return base + it.optString("Id") + "/Images/Backdrop/0?maxWidth=1280&tag=" + own.optString(0);
        }
        JSONObject tags = it.optJSONObject("ImageTags");
        if (tags != null && tags.has("Thumb")) {
            return base + it.optString("Id") + "/Images/Thumb?maxWidth=1280&tag=" + tags.optString("Thumb");
        }
        if (tags != null && tags.has("Primary")) {
            return base + it.optString("Id") + "/Images/Primary?maxWidth=1280&tag=" + tags.optString("Primary");
        }
        return null;
    }

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

    private static JSONObject conf(File dir) {
        File f = Shelf.secret("jellyfin.json");
        if (f == null || !f.isFile()) return null;
        try (InputStream in = new FileInputStream(f)) {
            return new JSONObject(read(in));
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
