package com.local.albumlock;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Shared settings and the helpers that put the user's normal lock wallpapers back. */
final class Prefs {
    static final String TAG = "AlbumLock";

    private Prefs() {}

    static SharedPreferences get(Context ctx) {
        return ctx.getSharedPreferences("albumlock", Context.MODE_PRIVATE);
    }

    static boolean enabled(Context ctx) {
        return get(ctx).getBoolean("enabled", true);
    }

    static void setEnabled(Context ctx, boolean on) {
        get(ctx).edit().putBoolean("enabled", on).apply();
    }

    /** Folder with the user's normal lock-screen photos, picked once in the app. */
    static File defaultsDir(Context ctx) {
        return new File(ctx.getFilesDir(), "defaults");
    }

    static List<File> defaults(Context ctx) {
        File[] files = defaultsDir(ctx).listFiles((d, name) -> name.endsWith(".jpg"));
        List<File> list = new ArrayList<>();
        if (files != null) {
            Arrays.sort(files);
            list.addAll(Arrays.asList(files));
        }
        return list;
    }

    static boolean hasDefaults(Context ctx) {
        return !defaults(ctx).isEmpty();
    }

    /** Sets the current normal photo back on the lock screen. Call off the main thread. */
    static boolean restoreDefault(Context ctx) {
        return setDefault(ctx, get(ctx).getInt("index", 0));
    }

    /** Moves on to the next normal photo, like Samsung's own rotation. Call off the main thread. */
    static boolean restoreNext(Context ctx) {
        return setDefault(ctx, get(ctx).getInt("index", 0) + 1);
    }

    private static boolean setDefault(Context ctx, int index) {
        List<File> list = defaults(ctx);
        if (list.isEmpty()) return false;
        index = Math.floorMod(index, list.size());
        get(ctx).edit().putInt("index", index).apply();
        try (InputStream in = new FileInputStream(list.get(index))) {
            WallpaperManager.getInstance(ctx).setStream(in, null, true, WallpaperManager.FLAG_LOCK);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "restore failed", e);
            return false;
        }
    }
}
