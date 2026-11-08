package com.knz.nekplik.share;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;
import java.util.Map;

// The one screen: what this device would share, and a button to start or stop. Works on a phone with
// a finger and on a TV box with a remote.
public class MainActivity extends Activity {
    private static final int RED = 0xFFE50914, DIM = 0xB3FFFFFF, BG = 0xFF0E0A0C;
    private final Handler main = new Handler(Looper.getMainLooper());
    private static final int PICK = 3;
    private TextView status, drives, button, access, choose, forget, link;
    private LinearLayout webBox;
    private ImageView qr;
    private Server peek;
    private volatile boolean looking;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            show();
            main.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        peek = new Server(this, null);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(28), dp(36), dp(28), dp(36));

        ImageView logo = new ImageView(this);
        logo.setImageResource(getResources().getIdentifier("logo", "drawable", getPackageName())); // no R class
        logo.setAdjustViewBounds(true);
        logo.setScaleType(ImageView.ScaleType.FIT_START);
        col.addView(logo, new LinearLayout.LayoutParams(-2, dp(34)));
        TextView sub = text(20, Color.WHITE, true);
        sub.setText("Share");
        sub.setPadding(0, dp(6), 0, dp(24));
        col.addView(sub);

        status = text(18, Color.WHITE, false);
        status.setLineSpacing(dp(4), 1f);
        col.addView(status);

        // While sharing: the web version's address, big, with Open, Copy and Share.
        webBox = new LinearLayout(this);
        webBox.setOrientation(LinearLayout.VERTICAL);
        webBox.setPadding(dp(18), dp(16), dp(18), dp(16));
        GradientDrawable box = new GradientDrawable();
        box.setCornerRadius(dp(12));
        box.setColor(0xFF221A1D);
        box.setStroke(dp(1), 0x40FFFFFF);
        webBox.setBackground(box);
        TextView label = text(13, DIM, true);
        label.setText("WEB VERSION  ·  open in a browser on the same Wi-Fi");
        webBox.addView(label);
        link = text(26, Color.WHITE, true);
        link.setTextIsSelectable(true);
        link.setPadding(0, dp(6), 0, dp(14));
        webBox.addView(link);
        LinearLayout actions = new LinearLayout(this);
        TextView open = small("Open"), copy = small("Copy"), share = small("Share");
        open.setOnClickListener(v -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link.getText().toString()))); } catch (Exception ignored) {}
        });
        copy.setOnClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("NEKPLIK", link.getText()));
            android.widget.Toast.makeText(this, "Link copied", android.widget.Toast.LENGTH_SHORT).show();
        });
        share.setOnClickListener(v -> {
            try {
                startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, "Watch our movies on NEKPLIK: " + link.getText()), "Share the link"));
            } catch (Exception ignored) {}
        });
        for (TextView b : new TextView[]{open, copy, share}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(10);
            actions.addView(b, lp);
        }
        webBox.addView(actions);
        // The same link as a QR code, for others to scan with their phone's camera.
        qr = new ImageView(this);
        qr.setContentDescription("QR code for the web version");
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(dp(200), dp(200));
        qp.topMargin = dp(16);
        webBox.addView(qr, qp);
        TextView scan = text(13, DIM, false);
        scan.setText("Scan with a phone's camera to open it.");
        scan.setPadding(0, dp(8), 0, 0);
        webBox.addView(scan);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(-1, -2);
        wp.topMargin = dp(18);
        webBox.setVisibility(LinearLayout.GONE);
        col.addView(webBox, wp);
        drives = text(16, DIM, false);
        drives.setLineSpacing(dp(4), 1f);
        drives.setPadding(0, dp(16), 0, dp(24));
        col.addView(drives);

        access = button("Allow access to drives");
        access.setOnClickListener(v -> askAccess());
        col.addView(access, wrap(dp(12)));
        button = button("Start sharing");
        button.setOnClickListener(v -> {
            if (ShareService.running) ShareService.stop(this);
            else {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 2);
                ShareService.start(this);
            }
            main.postDelayed(this::show, 400);
        });
        col.addView(button, wrap(dp(12)));
        // Phones keep USB drives from apps until the drive is chosen once in Android's picker.
        choose = button("Choose a USB drive");
        choose.setOnClickListener(v -> {
            try {
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), PICK);
            } catch (Exception e) {
                status.setText("This device has no file picker. Its drives are shared without one, if it lets apps read them.");
            }
        });
        col.addView(choose, wrap(dp(12)));
        forget = button("Forget chosen drives");
        forget.setOnClickListener(v -> {
            for (String t : Server.chosen(this)) {
                Uri u = Uri.parse(t);
                Server.choose(this, u, false);
                try { getContentResolver().releasePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
            }
            show();
        });
        col.addView(forget, wrap(dp(28)));

        TextView how = text(15, DIM, false);
        how.setPadding(0, dp(8), 0, 0);
        how.setLineSpacing(dp(3), 1f);
        how.setText("Plug a USB drive or hard disk into this device (with an OTG adapter on a phone), with a Movies or Series folder "
                + "at its top, laid out as for NEKPLIK. On a phone, tap Choose a USB drive and pick the drive itself (from the menu, "
                + "or the drive's name): this is needed once per drive. Or put them in a Nekplik folder on this device's own storage.\n\n"
                + "Keep this device on the same Wi-Fi as the TV. Sharing goes on with the screen off; stop it from here or "
                + "the notification. It stops by itself after two hours with no TV using it.");
        col.addView(how);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.addView(col);
        setContentView(scroll);
        button.requestFocus();
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri tree = data.getData();
        try { getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
        Server.choose(this, tree, true);
        show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        main.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(refresh);
    }

    private boolean canRead() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    // "All files access" from Android 11 (a switch in Settings), else the storage permission.
    private void askAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                try { startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); }
                catch (Exception x) {
                    status.setText("This device has no screen to allow it. From a computer, run:\nadb shell appops set "
                            + getPackageName() + " MANAGE_EXTERNAL_STORAGE allow");
                }
            }
        } else if (Build.VERSION.SDK_INT >= 23) requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
    }

    private void show() {
        boolean ok = canRead();
        access.setVisibility(ok ? TextView.GONE : TextView.VISIBLE);
        button.setEnabled(ok);
        button.setAlpha(ok ? 1f : 0.4f);
        button.setText(ShareService.running ? "Stop sharing" : "Start sharing");
        if (!ok) {
            status.setText("First, let NEKPLIK Share read this device's drives.");
            drives.setText("");
            return;
        }
        if (ShareService.problem != null && !ShareService.running) status.setText(ShareService.problem);
        else if (ShareService.running) {
            String p = ShareService.nowPlaying;
            String web = Server.address(ShareService.port);
            status.setText("Sharing as \"" + Server.deviceName(this) + "\". Open NEKPLIK on the TV: it finds this by itself."
                    + (p != null ? "\n\nPlaying: " + p : ""));
            if (web != null && !web.contentEquals(link.getText())) {
                link.setText(web);
                qr.setImageBitmap(qrBitmap(web));
            }
        } else status.setText("Not sharing.");
        webBox.setVisibility(ShareService.running && Server.address(ShareService.port) != null ? LinearLayout.VISIBLE : LinearLayout.GONE);

        forget.setVisibility(Server.chosen(this).isEmpty() ? TextView.GONE : TextView.VISIBLE);
        if (looking) return;
        looking = true;
        new Thread(() -> { // reading a drive can take a moment
            Map<String, List<String>> all = peek.overview();
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, List<String>> e : all.entrySet()) {
                sb.append("•  ").append(e.getKey()).append(":  ");
                sb.append(e.getValue().isEmpty() ? "no Movies or Series folder at its top" : TextUtils.join(", ", e.getValue()));
                sb.append('\n');
            }
            String text = sb.length() == 0 ? "No drive found yet. On a phone, tap Choose a USB drive." : sb.toString().trim();
            main.post(() -> { drives.setText(text); looking = false; });
        }, "overview").start();
    }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return t;
    }

    // A red pill button, white-edged when a remote's focus is on it.
    private TextView button(String label) {
        TextView b = text(17, Color.WHITE, true);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setClickable(true);
        b.setPadding(dp(28), dp(14), dp(28), dp(14));
        GradientDrawable off = new GradientDrawable();
        off.setCornerRadius(dp(999));
        off.setColor(RED);
        GradientDrawable on = new GradientDrawable();
        on.setCornerRadius(dp(999));
        on.setColor(RED);
        on.setStroke(dp(3), Color.WHITE);
        b.setBackground(off);
        b.setOnFocusChangeListener((v, has) -> b.setBackground(has ? on : off));
        return b;
    }

    // Black on white with the quiet border scanners need, one pixel a module, drawn sharp when scaled.
    private static android.graphics.Bitmap qrBitmap(String text) {
        boolean[][] m = QrCode.encode(text);
        int q = 4, n = m.length, w = n + 2 * q;
        int[] px = new int[w * w];
        for (int y = 0; y < w; y++)
            for (int x = 0; x < w; x++) {
                int mx = x - q, my = y - q;
                px[y * w + x] = mx >= 0 && my >= 0 && mx < n && my < n && m[my][mx] ? Color.BLACK : Color.WHITE;
            }
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(px, w, w, android.graphics.Bitmap.Config.ARGB_8888);
        return android.graphics.Bitmap.createScaledBitmap(b, w * 12, w * 12, false);
    }

    // A small grey pill button, for the link's actions.
    private TextView small(String label) {
        TextView b = text(15, Color.WHITE, true);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setClickable(true);
        b.setPadding(dp(18), dp(9), dp(18), dp(9));
        GradientDrawable off = new GradientDrawable();
        off.setCornerRadius(dp(999));
        off.setColor(0x33FFFFFF);
        GradientDrawable on = new GradientDrawable();
        on.setCornerRadius(dp(999));
        on.setColor(0x33FFFFFF);
        on.setStroke(dp(2), Color.WHITE);
        b.setBackground(off);
        b.setOnFocusChangeListener((v, has) -> b.setBackground(has ? on : off));
        return b;
    }

    private static LinearLayout.LayoutParams wrap(int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.bottomMargin = bottom;
        return p;
    }

    private int dp(float v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics())); }
}
