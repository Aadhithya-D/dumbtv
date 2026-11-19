package tv.dumbtv.home;

import android.content.ComponentName;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Recently watched, for every app: most players announce what they play (title, picture,
 * position) through a media session so the remote's buttons and the system can follow along.
 * As a notification listener (allowed once over adb: {@code cmd notification allow_listener})
 * this service may read those sessions; Fire OS keeps it running, so it records whatever plays
 * even while dumbtv home is not on screen, and hands each title to {@link Shelf}.
 *
 * <p>It reads nothing from notifications themselves and sends nothing anywhere.
 */
public final class Recorder extends NotificationListenerService {
    /** Shorter than this is an ad, a trailer or a preview, not something to continue. */
    private static final long MIN_DURATION_MS = 90_000;
    private static final long TICK_MS = 30_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService disk = Executors.newSingleThreadExecutor();
    private final Map<MediaSession.Token, Watch> watches = new HashMap<>();
    private MediaSessionManager sessions;
    private ComponentName me;
    private final MediaSessionManager.OnActiveSessionsChangedListener changed = this::track;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            for (Watch w : watches.values()) if (w.playing()) w.record();
            main.postDelayed(this, TICK_MS);
        }
    };

    @Override
    public void onListenerConnected() {
        Shelf.open(this);
        disk.execute(Shelf::forgetUnrecorded);
        disk.execute(Shelf::enrich);
        sessions = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
        me = new ComponentName(this, Recorder.class);
        try {
            sessions.addOnActiveSessionsChangedListener(changed, me, main);
            track(sessions.getActiveSessions(me));
        } catch (SecurityException e) {
            // Not allowed (yet): nothing to record.
            return;
        }
        main.removeCallbacks(tick);
        main.postDelayed(tick, TICK_MS);
    }

    @Override
    public void onListenerDisconnected() {
        main.removeCallbacks(tick);
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(changed);
        for (Watch w : watches.values()) w.stop();
        watches.clear();
    }

    private void track(List<MediaController> active) {
        if (active == null) active = new ArrayList<>();
        List<MediaSession.Token> now = new ArrayList<>();
        for (MediaController c : active) {
            if (getPackageName().equals(c.getPackageName())) continue;
            now.add(c.getSessionToken());
            if (!watches.containsKey(c.getSessionToken())) {
                Watch w = new Watch(c);
                watches.put(c.getSessionToken(), w);
                w.start();
            }
        }
        for (MediaSession.Token t : new ArrayList<>(watches.keySet())) {
            if (!now.contains(t)) watches.remove(t).stop();
        }
    }

    /** One app's session: keeps its latest title and position and records them as they change. */
    private final class Watch extends MediaController.Callback {
        final MediaController controller;
        final String pkg;
        MediaMetadata meta;
        PlaybackState state;
        String lastImageKey;
        String lastImage;

        Watch(MediaController c) {
            controller = c;
            pkg = c.getPackageName();
        }

        void start() {
            controller.registerCallback(this, main);
            meta = controller.getMetadata();
            state = controller.getPlaybackState();
            debug(pkg, "session", meta, state);
            if (playing()) record();
        }

        void stop() {
            if (meta != null) record();
            controller.unregisterCallback(this);
            // Stremio keeps its own Continue Watching: fetch it now that playback ended.
            if (Stremio.PKG.equals(pkg)) disk.execute(() -> Stremio.refresh(Shelf.dir(), true));
            if (Jellyfin.PKG.equals(pkg)) disk.execute(() -> Jellyfin.refresh(Shelf.dir(), true));
        }

        boolean playing() {
            return state != null && state.getState() == PlaybackState.STATE_PLAYING;
        }

        @Override
        public void onMetadataChanged(MediaMetadata m) {
            // The title that was playing keeps its last position before the next one takes over.
            if (meta != null && !sameTitle(meta, m)) record();
            meta = m;
            debug(pkg, "metadata", meta, state);
            if (playing()) record();
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState s) {
            boolean was = playing();
            state = s;
            if (s != null && was != playing()) {
                debug(pkg, "state", meta, state);
                record();
                if (was && Stremio.PKG.equals(pkg)) disk.execute(() -> Stremio.refresh(Shelf.dir(), true));
                if (was && Jellyfin.PKG.equals(pkg)) disk.execute(() -> Jellyfin.refresh(Shelf.dir(), true));
            }
        }

        @Override
        public void onSessionDestroyed() {
            stop();
            watches.remove(controller.getSessionToken());
        }

        /** Hands the current title to the shelf, with its picture saved next to shelf.json. */
        void record() {
            if (Shelf.UNRECORDED.contains(pkg)) return;
            final Shelf.Entry e = entry();
            if (e == null) return;
            final Bitmap art = art(meta);
            final String key = pkg + "|" + e.title;
            if (art != null && !key.equals(lastImageKey)) {
                lastImageKey = key;
                disk.execute(() -> {
                    String saved = save(key, art);
                    lastImage = saved;
                    if (saved != null && e.image == null) e.image = saved;
                    Shelf.played(e);
                });
                return;
            }
            if (e.image == null && key.equals(lastImageKey)) e.image = lastImage;
            disk.execute(() -> Shelf.played(e));
        }

        private Shelf.Entry entry() {
            if (meta == null) return null;
            String title = text(meta, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
            if (title == null) return null;
            long duration = meta.getLong(MediaMetadata.METADATA_KEY_DURATION);
            if (duration > 0 && duration < MIN_DURATION_MS) return null;
            Shelf.Entry e = new Shelf.Entry();
            e.pkg = pkg;
            e.title = title;
            String id = meta.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
            e.id = id == null ? title : id;
            String sub = text(meta, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
                    MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM);
            e.subtitle = sub == null || sub.equals(title) ? "" : sub;
            String desc = text(meta, MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION);
            e.description = desc == null ? "" : desc;
            e.duration = Math.max(0, duration);
            e.progress = position();
            e.watched = System.currentTimeMillis();
            // No link: MEDIA_URI is the stream itself (Hotstar's is a CDN address), not a page the
            // app can open, so OK on the card opens the app instead.
            e.link = link(pkg, id, e.image);
            e.image = web(text(meta, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
                    MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI));
            return e;
        }

        /** Where playback is now: the last reported position moved on by the time since. */
        private long position() {
            if (state == null) return 0;
            long pos = state.getPosition();
            if (state.getState() == PlaybackState.STATE_PLAYING && state.getLastPositionUpdateTime() > 0) {
                pos += (long) ((SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime())
                        * state.getPlaybackSpeed());
            }
            return Math.max(0, pos);
        }
    }

    /**
     * A link that opens the title itself, for apps whose ids say enough. Zee5: the episode id
     * (0-1-…) and the show id inside its banner's address (resources/0-6-…) make the web link
     * its app opens straight into the player; a film (0-0-…) needs only its own id. Others:
     * null, and the card opens the app.
     */
    static String link(String pkg, String id, String image) {
        if (id == null) return null;
        if ("com.zee5.amazon".equals(pkg)) {
            if (id.startsWith("0-0-")) return "https://www.zee5.com/movies/details/title/" + id;
            if (id.startsWith("0-1-") && image != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("resources/(0-6-[0-9a-z]+)/").matcher(image);
                if (m.find()) return "https://www.zee5.com/tv-shows/details/show/" + m.group(1) + "/episode/" + id;
            }
        }
        return null;
    }

    private static boolean sameTitle(MediaMetadata a, MediaMetadata b) {
        if (b == null) return false;
        String x = text(a, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
        String y = text(b, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
        return x != null && x.equals(y);
    }

    private static String text(MediaMetadata m, String... keys) {
        for (String k : keys) {
            CharSequence v = m.getText(k);
            if (v != null && v.toString().trim().length() > 0) return v.toString().trim();
        }
        return null;
    }

    private static String web(String uri) {
        return uri != null && (uri.startsWith("http://") || uri.startsWith("https://")) ? uri : null;
    }

    private static Bitmap art(MediaMetadata m) {
        if (m == null) return null;
        for (String k : new String[] {MediaMetadata.METADATA_KEY_DISPLAY_ICON,
                MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART}) {
            Bitmap b = m.getBitmap(k);
            if (b != null && !b.isRecycled() && b.getWidth() >= 64) return b;
        }
        return null;
    }

    /** Saves a session's picture as a file the shelf can show; returns its file: URI. */
    private String save(String key, Bitmap b) {
        File dir = Shelf.imageDir();
        if (dir == null) return null;
        dir.mkdirs();
        File f = new File(dir, "played-" + Integer.toHexString(key.hashCode()) + ".jpg");
        File part = new File(dir, f.getName() + ".part");
        try (OutputStream out = new FileOutputStream(part)) {
            if (!b.compress(Bitmap.CompressFormat.JPEG, 90, out)) return null;
        } catch (Exception e) {
            return null;
        }
        return part.renameTo(f) ? "file:" + f.getPath() : null;
    }

    /**
     * What each app announces, for checking which apps give a title, picture and position.
     * A small rolling file next to shelf.json; it never leaves the TV.
     */
    private void debug(String pkg, String why, MediaMetadata m, PlaybackState s) {
        final StringBuilder line = new StringBuilder();
        line.append(System.currentTimeMillis()).append(' ').append(pkg).append(' ').append(why);
        if (s != null) line.append(" state=").append(s.getState()).append(" pos=").append(s.getPosition());
        if (m != null) {
            for (String k : m.keySet()) {
                line.append(" | ").append(k.replace("android.media.metadata.", "")).append('=');
                Bitmap b = m.getBitmap(k);
                if (b != null) line.append("bitmap ").append(b.getWidth()).append('x').append(b.getHeight());
                else {
                    CharSequence t = m.getText(k);
                    if (t != null) line.append(t.length() > 120 ? t.subSequence(0, 120) + "…" : t);
                    else line.append(m.getLong(k));
                }
            }
        }
        disk.execute(() -> {
            File dir = Shelf.dir();
            if (dir == null) return;
            File f = new File(dir, "played-debug.txt");
            if (f.length() > 256_000) f.renameTo(new File(dir, "played-debug.old.txt"));
            try (FileWriter w = new FileWriter(f, true)) {
                w.write(line.append('\n').toString());
            } catch (Exception ignored) {
                // debugging only
            }
        });
    }
}
