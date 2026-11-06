package com.knz.nekplix.share;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

// Installing a new version stops the app, sharing included. If it was sharing (not stopped by hand, or
// for being unused), it starts again by itself, so the TV and browsers don't lose it.
public class Updated extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction()) || !ShareService.wasSharing(c)) return;
        try { ShareService.start(c); } catch (Exception ignored) {} // some phones refuse; then it's the Start button
    }
}
