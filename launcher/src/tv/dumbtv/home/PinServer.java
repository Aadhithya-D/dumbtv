package tv.dumbtv.home;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small page on the home network for pinning shows from a phone: paste a show's link from
 * the service's website or share sheet, and it lands on that app's Top Shelf.
 * http://&lt;TV address&gt;:{@link #PORT}/ — no account, nothing leaves the house but the one
 * request for the show's page, to read its name and poster.
 */
final class PinServer {
    static final int PORT = 8080;
    private static final String UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Safari/537.36";
    private static boolean started;

    private PinServer() {}

    static synchronized void start(Context c) {
        if (started) return;
        started = true;
        Context app = c.getApplicationContext();
        Thread t = new Thread(() -> serve(app), "pin-server");
        t.setDaemon(true);
        t.start();
    }

    private static void serve(Context c) {
        while (true) {
            try (ServerSocket server = new ServerSocket()) {
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(PORT));
                while (true) {
                    Socket s = server.accept();
                    Thread w = new Thread(() -> handle(c, s), "pin-request");
                    w.setDaemon(true);
                    w.start();
                }
            } catch (Exception e) {
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private static void handle(Context c, Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(10_000);
            InputStream in = sock.getInputStream();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String request = r.readLine();
            if (request == null) return;
            int length = 0;
            for (String h; (h = r.readLine()) != null && !h.isEmpty(); ) {
                if (h.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                    length = Integer.parseInt(h.substring(15).trim());
                }
            }
            char[] body = new char[Math.min(length, 16384)];
            int got = 0;
            while (got < body.length) {
                int n = r.read(body, got, body.length - got);
                if (n < 0) break;
                got += n;
            }
            Map<String, String> form = form(new String(body, 0, got));
            String note = "";
            if (request.startsWith("POST /pin")) note = pin(form.get("link"), form.get("name"));
            else if (request.startsWith("POST /unpin")) note = unpin(form.get("pkg"), form.get("title"));
            if (request.startsWith("POST")) {
                // Back to the page, so a refresh does not send the form again.
                String loc = "/?note=" + Uri.encode(note);
                write(sock.getOutputStream(), "303 See Other", "Location: " + loc + "\r\n", "");
                return;
            }
            String q = request.contains("?note=") ? request.substring(request.indexOf("?note=") + 6).split(" ")[0] : "";
            write(sock.getOutputStream(), "200 OK", "", page(Uri.decode(q)));
        } catch (Exception ignored) {
            // a dropped connection
        }
    }

    private static void write(OutputStream out, String status, String headers, String html) throws java.io.IOException {
        byte[] b = html.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + "\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + b.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n" + headers + "\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }

    private static Map<String, String> form(String body) {
        Map<String, String> out = new HashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            try {
                out.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"), URLDecoder.decode(pair.substring(eq + 1), "UTF-8").trim());
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    // ---- pinning ----

    /** The TV app for a show's web link, or null when it is not one dumbtv knows. */
    static String appFor(String host) {
        host = host.toLowerCase(Locale.ROOT);
        if (host.endsWith("netflix.com")) return "com.netflix.ninja";
        if (host.endsWith("hotstar.com")) return "in.startv.hotstar";
        if (host.endsWith("zee5.com")) return "com.zee5.amazon";
        if (host.endsWith("sunnxt.com")) return "com.suntv.sunnxt";
        if (host.endsWith("primevideo.com") || host.contains("amazon.")) return "com.amazon.firebat";
        if (host.endsWith("stremio.com") || host.endsWith("strem.io")) return "com.stremio.one";
        return null;
    }

    private static String pin(String link, String name) {
        if (link == null || link.isEmpty()) return "Paste a link first.";
        Matcher url = Pattern.compile("https?://\\S+").matcher(link);
        if (!url.find()) return "That doesn't look like a link.";
        link = url.group();
        Uri u = Uri.parse(link);
        String pkg = u.getHost() == null ? null : appFor(u.getHost());
        if (pkg == null) return "dumbtv doesn't know which app opens " + u.getHost() + ".";

        Shelf.Entry e = new Shelf.Entry();
        e.pkg = pkg;
        e.id = link;
        e.link = tvLink(pkg, u);
        String[] meta = meta(link);
        e.title = name != null && !name.isEmpty() ? name : meta[0] != null ? clean(meta[0]) : fromPath(u);
        e.image = meta[1];
        e.description = meta[2] == null ? "" : meta[2];
        Shelf.pin(e, true);
        return "Pinned " + e.title + ".";
    }

    private static String unpin(String pkg, String title) {
        if (pkg == null || title == null) return "";
        Shelf.Entry e = new Shelf.Entry();
        e.pkg = pkg;
        e.title = title;
        Shelf.pin(e, false);
        return "Unpinned " + title + ".";
    }

    /** The link the TV app understands for a web link. */
    private static String tvLink(String pkg, Uri u) {
        String path = u.getPath() == null ? "" : u.getPath();
        if ("com.netflix.ninja".equals(pkg)) {
            Matcher m = Pattern.compile("/title/(\\d+)").matcher(path);
            if (m.find()) return "https://www.netflix.com/title/" + m.group(1);
        }
        if ("com.stremio.one".equals(pkg)) {
            // web.stremio.com/#/detail/series/tt… -> stremio:///detail/series/tt…
            String frag = u.getFragment();
            if (frag != null && frag.startsWith("/detail/")) return "stremio://" + frag;
        }
        return u.toString();
    }

    /** og:title, og:image and og:description of the page, each null when missing. */
    private static String[] meta(String link) {
        String[] out = new String[3];
        HttpURLConnection http = null;
        try {
            http = (HttpURLConnection) new URL(link).openConnection();
            http.setConnectTimeout(5000);
            http.setReadTimeout(6000);
            http.setRequestProperty("User-Agent", UA);
            http.setRequestProperty("Accept-Language", "en-IN,en;q=0.9");
            StringBuilder html = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(http.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buf = new char[8192];
                for (int n; html.length() < 400_000 && (n = r.read(buf)) > 0; ) html.append(buf, 0, n);
            }
            out[0] = og(html, "og:title");
            out[1] = og(html, "og:image");
            out[2] = og(html, "og:description");
            if (out[1] != null && out[1].startsWith("/")) out[1] = new URL(new URL(link), out[1]).toString();
            // A site's own logo is no poster.
            if (out[1] != null && out[1].contains("/logo")) out[1] = null;
        } catch (Exception ignored) {
            // blocked or offline: the name comes from the link instead
        } finally {
            if (http != null) http.disconnect();
        }
        return out;
    }

    private static String og(CharSequence html, String key) {
        Matcher m = Pattern.compile("<meta[^>]+property=\"" + key + "\"[^>]+content=\"([^\"]*)\"").matcher(html);
        if (!m.find()) m = Pattern.compile("<meta[^>]+content=\"([^\"]*)\"[^>]+property=\"" + key + "\"").matcher(html);
        if (!m.find(0)) return null;
        String v = m.group(1).replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&#x27;", "'").replace("&lt;", "<").replace("&gt;", ">").trim();
        return v.isEmpty() ? null : v;
    }

    /** "Watch The Mentalist | Netflix" -> "The Mentalist". */
    private static String clean(String title) {
        String t = title.replaceFirst("^(?i)watch\\s+", "");
        int bar = t.indexOf(" | ");
        if (bar > 0) t = t.substring(0, bar);
        int dash = t.lastIndexOf(" - ");
        if (dash > 0 && t.length() - dash < 30) t = t.substring(0, dash);
        return t.trim();
    }

    /** ".../shows/the-night-manager/1271207400" -> "The Night Manager". */
    private static String fromPath(Uri u) {
        String best = "";
        for (String seg : u.getPathSegments()) {
            if (seg.matches(".*[a-zA-Z].*") && seg.contains("-") && seg.length() > best.length()) best = seg;
        }
        if (best.isEmpty()) return u.getHost();
        StringBuilder out = new StringBuilder();
        for (String w : best.split("-")) {
            if (w.isEmpty()) continue;
            out.append(out.length() == 0 ? "" : " ").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return out.toString();
    }

    // ---- the page ----

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String page(String note) {
        StringBuilder b = new StringBuilder();
        b.append("<!doctype html><html><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>")
                .append("<title>dumbtv pins</title><style>")
                .append("body{margin:0;background:#0a0608;color:#f2f2f3;font:16px/1.4 -apple-system,system-ui,sans-serif;padding:20px 16px}")
                .append("h1{font-size:22px;margin:0 0 4px}p.s{color:#9a9a9f;margin:0 0 18px;font-size:14px}")
                .append("textarea,input{width:100%;box-sizing:border-box;background:#1c1d21;color:#fff;border:1px solid #333;border-radius:10px;padding:12px;font:inherit;margin-bottom:10px}")
                .append("button{background:#fff;color:#111;border:0;border-radius:10px;padding:12px 18px;font:600 16px system-ui;width:100%}")
                .append(".note{background:#1f3a24;border-radius:10px;padding:10px 12px;margin-bottom:16px}")
                .append("h2{font-size:13px;letter-spacing:.12em;color:#9a9a9f;margin:26px 0 8px}")
                .append(".pin{display:flex;align-items:center;gap:12px;padding:8px 0;border-bottom:1px solid #222}")
                .append(".pin img,.pin .ph{width:96px;height:54px;border-radius:6px;object-fit:cover;background:#1c1d21;flex:none}")
                .append(".pin .t{flex:1;min-width:0}.pin .t b{display:block;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}")
                .append(".pin .t span{color:#9a9a9f;font-size:13px}.pin form button{background:#2a2b30;color:#fff;width:auto;padding:8px 12px;font-size:14px}")
                .append("</style></head><body><h1>Pin a show</h1>")
                .append("<p class=s>Paste a link from the Netflix, JioHotstar, ZEE5, Sun NXT, Prime Video or Stremio website, or from Share in their phone apps. It appears on that app's Top Shelf on the TV.</p>");
        if (note != null && !note.isEmpty()) b.append("<div class=note>").append(esc(note)).append("</div>");
        b.append("<form method=post action=/pin><textarea name=link rows=3 placeholder='https://www.hotstar.com/in/shows/…' required></textarea>")
                .append("<input name=name placeholder='Name (optional, if the TV gets it wrong)'><button>Pin to TV</button></form>");
        List<Shelf.Entry> pins = Shelf.allPins();
        if (!pins.isEmpty()) b.append("<h2>PINNED</h2>");
        for (int i = pins.size() - 1; i >= 0; i--) {
            Shelf.Entry e = pins.get(i);
            b.append("<div class=pin>")
                    .append(e.image != null ? "<img src='" + esc(e.image) + "' alt=''>" : "<div class=ph></div>")
                    .append("<div class=t><b>").append(esc(e.title)).append("</b><span>").append(esc(appName(e.pkg))).append("</span></div>")
                    .append("<form method=post action=/unpin><input type=hidden name=pkg value='").append(esc(e.pkg))
                    .append("'><input type=hidden name=title value='").append(esc(e.title)).append("'><button>Remove</button></form></div>");
        }
        return b.append("</body></html>").toString();
    }

    private static String appName(String pkg) {
        switch (pkg) {
            case "com.netflix.ninja": return "Netflix";
            case "in.startv.hotstar": return "JioHotstar";
            case "com.zee5.amazon": return "ZEE5";
            case "com.suntv.sunnxt": return "Sun NXT";
            case "com.amazon.firebat": return "Prime Video";
            case "com.stremio.one": return "Stremio";
            default: return pkg;
        }
    }
}
