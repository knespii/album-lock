package com.local.albumlock;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Setup screen: grant access, pick the normal wallpaper, switch on or off. */
public class MainActivity extends Activity {
    private static final int PICK_IMAGE = 1;

    private TextView status;
    private ImageView preview;
    private Switch enabled;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);

        int pad = dp(24);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Album Lock");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        col.addView(title);

        TextView about = text("Při přehrávání hudby nastaví obal alba jako tapetu zamykací obrazovky. "
                + "Když hudba 90 s nehraje, vrátí tvoje normální fotky. Pokud jich vybereš víc, "
                + "střídají se při každém zhasnutí displeje.");
        col.addView(about);

        status = text("");
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        col.addView(status);

        Button access = new Button(this);
        access.setText("1. Povolit přístup k oznámením");
        access.setOnClickListener(v -> openListenerSettings());
        col.addView(access);

        Button pick = new Button(this);
        pick.setText("2. Vybrat normální fotky zamykací obrazovky");
        pick.setOnClickListener(v -> pickImage());
        col.addView(pick);

        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.setAdjustViewBounds(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(160), dp(340));
        lp.topMargin = dp(8);
        lp.bottomMargin = dp(8);
        col.addView(preview, lp);

        enabled = new Switch(this);
        enabled.setText("Zapnuto");
        enabled.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        enabled.setChecked(Prefs.enabled(this));
        enabled.setOnCheckedChangeListener((b, on) -> {
            Prefs.setEnabled(this, on);
            if (!on) restoreNow();
        });
        col.addView(enabled);

        Button restore = new Button(this);
        restore.setText("Vrátit normální tapetu teď");
        restore.setOnClickListener(v -> restoreNow());
        col.addView(restore);

        ScrollView root = new ScrollView(this);
        root.setFitsSystemWindows(true);
        root.addView(col, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean access = getSystemService(NotificationManager.class)
                .isNotificationListenerAccessGranted(new ComponentName(this, ArtListener.class));
        List<File> photos = Prefs.defaults(this);
        boolean hasDefault = !photos.isEmpty();

        StringBuilder s = new StringBuilder();
        s.append(access ? "✓ Přístup k oznámením povolen\n" : "✗ Chybí přístup k oznámením\n");
        s.append(hasDefault ? "✓ Vybrané normální fotky: " + photos.size() : "✗ Normální fotky nejsou vybrané");
        if (access && hasDefault) s.append("\n\nVše hotovo, pusť si hudbu a zamkni mobil.");
        status.setText(s);

        if (hasDefault) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 4;
            preview.setImageBitmap(BitmapFactory.decodeFile(photos.get(0).getAbsolutePath(), o));
        } else {
            preview.setImageDrawable(null);
        }
    }

    private void openListenerSettings() {
        ComponentName me = new ComponentName(this, ArtListener.class);
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, me.flattenToString()));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        }
    }

    private void pickImage() {
        Intent i = new Intent(MediaStore.ACTION_PICK_IMAGES)
                .setType("image/*")
                .putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 30);
        try {
            startActivityForResult(i, PICK_IMAGE);
        } catch (Exception e) {
            startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT)
                    .setType("image/*")
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), PICK_IMAGE);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req != PICK_IMAGE || res != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;

        new Thread(() -> {
            // Save into a fresh folder first, swap it in only when at least one photo worked.
            File fresh = new File(getFilesDir(), "defaults.new");
            deleteDir(fresh);
            fresh.mkdirs();
            int saved = 0;
            for (int i = 0; i < uris.size(); i++) {
                if (saveDefault(uris.get(i), new File(fresh, String.format("%03d.jpg", i)))) saved++;
            }
            boolean ok = saved > 0;
            if (ok) {
                File dir = Prefs.defaultsDir(this);
                deleteDir(dir);
                ok = fresh.renameTo(dir);
                Prefs.get(this).edit().putInt("index", 0).apply();
            } else {
                deleteDir(fresh);
            }
            final int count = saved;
            final boolean done = ok;
            runOnUiThread(() -> {
                toast(done ? "Uloženo fotek: " + count : "Fotky se nepodařilo načíst");
                refresh();
            });
        }).start();
    }

    private static void deleteDir(File dir) {
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        dir.delete();
    }

    /** Re-encodes a picked photo (also HEIC) as a JPEG of sensible size in private storage. */
    private boolean saveDefault(Uri uri, File target) {
        try {
            Bitmap bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(getContentResolver(), uri),
                    (decoder, info, src) -> {
                        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                        int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                        float scale = Math.min(1f, 3200f / Math.max(w, h));
                        decoder.setTargetSize(Math.round(w * scale), Math.round(h * scale));
                    });
            try (FileOutputStream out = new FileOutputStream(target)) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 95, out);
            }
            bmp.recycle();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void restoreNow() {
        new Thread(() -> {
            boolean ok = Prefs.restoreDefault(this);
            runOnUiThread(() -> toast(ok ? "Normální fotka vrácená" : "Nejdřív vyber normální fotky"));
        }).start();
    }

    private TextView text(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setPadding(0, dp(8), 0, dp(12));
        return t;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
