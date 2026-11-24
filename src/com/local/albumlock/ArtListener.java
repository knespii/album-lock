package com.local.albumlock;

import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.hardware.display.DisplayManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Watches media sessions (access comes with the notification-listener permission).
 * While something plays, the album cover becomes the lock-screen wallpaper;
 * once nothing has played for a while, the user's normal wallpaper goes back.
 */
public class ArtListener extends NotificationListenerService {
    private static final String TAG = Prefs.TAG;
    /** How long playback must be stopped or paused before the normal wallpaper returns. */
    private static final long RESTORE_DELAY_MS = 90_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<MediaSession.Token, MediaController> controllers = new HashMap<>();
    private final Map<MediaSession.Token, MediaController.Callback> callbacks = new HashMap<>();

    private MediaSessionManager sessions;
    private String lastKey;
    private int lastArtPixels;
    private boolean showingArt;
    /** Bumped on every request so an outdated background job never overwrites a newer one. */
    private volatile int generation;

    private final Runnable restoreTask = this::restoreNow;
    private boolean screenReceiverOn;
    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { onScreenOff(); }
    };
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = this::updateSessions;

    @Override
    public void onListenerConnected() {
        removeOldLyricsCache();
        sessions = getSystemService(MediaSessionManager.class);
        ComponentName me = new ComponentName(this, ArtListener.class);
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsChanged, me, main);
            updateSessions(sessions.getActiveSessions(me));
        } catch (SecurityException e) {
            Log.w(TAG, "no access to media sessions", e);
        }
        if (!screenReceiverOn) {
            registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));
            screenReceiverOn = true;
        }
    }

    @Override
    public void onListenerDisconnected() {
        if (screenReceiverOn) {
            unregisterReceiver(screenOff);
            screenReceiverOn = false;
        }
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(sessionsChanged);
        for (Map.Entry<MediaSession.Token, MediaController> e : controllers.entrySet()) {
            e.getValue().unregisterCallback(callbacks.get(e.getKey()));
        }
        controllers.clear();
        callbacks.clear();
        main.removeCallbacks(restoreTask);
    }

    /** An earlier test version cached song lyrics here; that feature is gone. */
    private void removeOldLyricsCache() {
        File dir = new File(getCacheDir(), "lyrics");
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        dir.delete();
    }

    @Override
    public void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }

    private void updateSessions(List<MediaController> list) {
        Set<MediaSession.Token> alive = new HashSet<>();
        if (list != null) {
            for (MediaController c : list) {
                MediaSession.Token t = c.getSessionToken();
                alive.add(t);
                if (controllers.containsKey(t)) continue;
                MediaController.Callback cb = new MediaController.Callback() {
                    @Override public void onPlaybackStateChanged(PlaybackState state) { evaluate(); }
                    @Override public void onMetadataChanged(MediaMetadata metadata) { evaluate(); }
                    @Override public void onSessionDestroyed() { evaluate(); }
                };
                c.registerCallback(cb, main);
                controllers.put(t, c);
                callbacks.put(t, cb);
            }
        }
        Iterator<Map.Entry<MediaSession.Token, MediaController>> it = controllers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<MediaSession.Token, MediaController> e = it.next();
            if (!alive.contains(e.getKey())) {
                e.getValue().unregisterCallback(callbacks.remove(e.getKey()));
                it.remove();
            }
        }
        evaluate();
    }

    private void evaluate() {
        // Without a saved normal wallpaper we could never undo the change, so do nothing.
        if (!Prefs.enabled(this) || !Prefs.hasDefaults(this)) return;

        MediaController playing = null;
        for (MediaController c : controllers.values()) {
            PlaybackState s = c.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING) {
                playing = c;
                break;
            }
        }

        if (playing == null) {
            if (showingArt) {
                main.removeCallbacks(restoreTask);
                main.postDelayed(restoreTask, RESTORE_DELAY_MS);
            }
            return;
        }
        main.removeCallbacks(restoreTask);

        MediaMetadata m = playing.getMetadata();
        if (m == null) return;
        Bitmap art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (art == null) return;

        // Same album means same cover: skip the wallpaper write unless a sharper cover arrived.
        String album = m.getString(MediaMetadata.METADATA_KEY_ALBUM);
        String who = m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        if (who == null) who = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
        String key = playing.getPackageName() + "|" + who + "|"
                + (album != null && !album.isEmpty() ? album : m.getString(MediaMetadata.METADATA_KEY_TITLE));
        int pixels = art.getWidth() * art.getHeight();
        if (key.equals(lastKey) && pixels <= lastArtPixels && showingArt) return;
        lastKey = key;
        lastArtPixels = pixels;
        showingArt = true;

        final Bitmap copy = art.copy(Bitmap.Config.ARGB_8888, false);
        final int gen = ++generation;
        worker.execute(() -> applyArt(copy, gen));
    }

    private void applyArt(Bitmap art, int gen) {
        if (gen != generation) return;
        try {
            DisplayMetrics dm = new DisplayMetrics();
            getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(dm);
            int w = Math.min(dm.widthPixels, dm.heightPixels);
            int h = Math.max(dm.widthPixels, dm.heightPixels);
            Bitmap out = Renderer.render(art, w, h);
            if (gen == generation) {
                // The system overwrites the same lock wallpaper file each time, so nothing piles up.
                // JPEG instead of setBitmap's PNG keeps each write ~10x smaller, and the temporary
                // cover is kept out of the device backup.
                ByteArrayOutputStream jpeg = new ByteArrayOutputStream(512 * 1024);
                out.compress(Bitmap.CompressFormat.JPEG, 92, jpeg);
                WallpaperManager.getInstance(this).setStream(
                        new ByteArrayInputStream(jpeg.toByteArray()), null, false, WallpaperManager.FLAG_LOCK);
            }
            out.recycle();
        } catch (Exception e) {
            Log.e(TAG, "setting cover failed", e);
        } finally {
            art.recycle();
        }
    }

    private void restoreNow() {
        if (!showingArt) return;
        showingArt = false;
        lastKey = null;
        lastArtPixels = 0;
        final int gen = ++generation;
        worker.execute(() -> {
            if (gen == generation) Prefs.restoreNext(this);
        });
    }

    /** Screen went off while no cover is shown: switch to the next normal photo, like Samsung does. */
    private void onScreenOff() {
        // Catch music that was already playing before setup finished or before the app started.
        evaluate();
        if (showingArt || !Prefs.enabled(this) || Prefs.defaults(this).size() < 2) return;
        final int gen = ++generation;
        worker.execute(() -> {
            if (gen == generation && !showingArt) Prefs.restoreNext(this);
        });
    }
}
