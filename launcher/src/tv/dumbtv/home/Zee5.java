package tv.dumbtv.home;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Zee5's ids, read from what its media session reports. A film is 0-0-…, an episode 0-1-…, a
 * show 0-6-…; the show id is not in the session itself, only in its picture's address
 * (…/resources/0-6-…/tv_banner/…).
 *
 * <p>The zee5.com web link, opened with the Zee5 app as its package, is what reliably opens that
 * title in the app, so the card keeps that link rather than the stream address or a plain app
 * launch. An episode opens its show's page, not the episode: the app knows which episode is
 * next and where it stopped better than the last episode the shelf saw.
 *
 * <p>The session names the episode, never the show. zee5.com answers a show link with a redirect
 * to the show's own address, whose slug is its name (…/details/sa-re-ga-ma-pa-2026/0-6-…); the
 * site and its catalogue refuse anything else without an account. Names are kept in
 * {@code zee5-names.properties}, one request per show.
 */
final class Zee5 {
    static final String PKG = "com.zee5.amazon";
    private static final Pattern SHOW = Pattern.compile("(?:resources|/show)/(0-6-[0-9a-z]+)(?:/|$)");
    private static final Pattern SLUG = Pattern.compile("/details/([0-9a-z-]+)/0-6-");
    private static Properties names;

    private Zee5() {}

    /**
     * The web link that opens this title in the app: a film's own page, an episode's show page;
     * null when the ids do not say enough.
     */
    static String link(String id, String image) {
        if (id == null) return null;
        if (id.startsWith("0-0-")) return "https://www.zee5.com/movies/details/title/" + id;
        String show = id.startsWith("0-6-") ? id : id.startsWith("0-1-") ? show(null, image) : null;
        return show == null ? null : "https://www.zee5.com/tv-shows/details/show/" + show;
    }

    /** "sa-re-ga-ma-pa-2026" from the show's address as "Sa Re Ga Ma Pa 2026"; null when absent. */
    static String nameFromAddress(String address) {
        if (address == null) return null;
        Matcher m = SLUG.matcher(address);
        if (!m.find()) return null;
        StringBuilder name = new StringBuilder();
        for (String w : m.group(1).split("-")) {
            if (w.isEmpty()) continue;
            if (name.length() > 0) name.append(' ');
            name.append(w.substring(0, 1).toUpperCase(Locale.ROOT)).append(w.substring(1));
        }
        return name.length() == 0 ? null : name.toString();
    }

    /** The show's name, from the cache or zee5.com; null when offline or not found. Network. */
    static synchronized String name(File dir, String show) {
        if (dir == null || show == null) return null;
        Properties c = names(dir);
        String hit = c.getProperty(show);
        if (hit != null) return hit.isEmpty() ? null : hit;
        HttpURLConnection http = null;
        try {
            http = (HttpURLConnection) new URL("https://www.zee5.com/tv-shows/details/show/" + show).openConnection();
            http.setInstanceFollowRedirects(false);
            http.setConnectTimeout(6000);
            http.setReadTimeout(8000);
            // Android's own agent: zee5.com turns away the default desktop-looking ones (403).
            http.setRequestProperty("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 9)");
            int code = http.getResponseCode();
            if (code / 100 != 3) return null;   // blocked or down: ask again next time
            hit = nameFromAddress(http.getHeaderField("Location"));
        } catch (Exception e) {
            return null;
        } finally {
            if (http != null) http.disconnect();
        }
        c.setProperty(show, hit == null ? "" : hit);
        try (OutputStream out = new FileOutputStream(new File(dir, "zee5-names.properties"))) {
            c.store(out, null);
        } catch (Exception ignored) {
            // still used this time
        }
        return hit;
    }

    private static Properties names(File dir) {
        if (names != null) return names;
        names = new Properties();
        try (InputStream in = new FileInputStream(new File(dir, "zee5-names.properties"))) {
            names.load(in);
        } catch (Exception ignored) {
            // none yet
        }
        return names;
    }

    /** The show an episode belongs to (0-6-…), from its link or its picture; null for a film. */
    static String show(String link, String image) {
        for (String s : new String[] {link, image}) {
            if (s == null) continue;
            Matcher m = SHOW.matcher(s);
            if (m.find()) return m.group(1);
        }
        return null;
    }
}
