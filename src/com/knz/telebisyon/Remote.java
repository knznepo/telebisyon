package com.knz.telebisyon;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Build;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

// Opt-in (remote.sh, or Settings > Accessibility > Telebisyon remote): what the /remote page needs that
// an app can't do by itself. It presses the remote's arrows, OK, Back and Home, and opens the power
// menu.
// Volume and play/pause don't need it (Messages does those).
//
// It only acts when the /remote page asks. It reads what's on screen only on TVs before Android 13,
// to move the highlight for the arrows.
public class Remote extends AccessibilityService {
    static volatile Remote self; // while it's on; the web server calls through this


    @Override
    protected void onServiceConnected() { self = this; }

    @Override
    public boolean onUnbind(Intent intent) {
        self = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        self = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    // up, down, left, right, ok, back, home, power (the TV's power menu).
    // Returns false for a key it doesn't know.
    boolean key(String k) {
        switch (k) {
            case "back": return performGlobalAction(GLOBAL_ACTION_BACK);
            case "home":
                // Telebisyon's home screen, the same as /home, so it works where Google TV's has to stay on.
                startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            case "power": return performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
            case "up": case "down": case "left": case "right": case "ok": return arrow(k);
            default: return false;
        }
    }

    // Android 13+ presses the real key. Before that, there's no way for an app to, so this moves the
    // highlight itself: to the next thing in that direction, or clicks it for OK. Most apps follow;
    // ones that draw their own screens (YouTube, say) may not.
    private boolean arrow(String k) {
        if (Build.VERSION.SDK_INT >= 33) {
            switch (k) {
                case "up": return performGlobalAction(GLOBAL_ACTION_DPAD_UP);
                case "down": return performGlobalAction(GLOBAL_ACTION_DPAD_DOWN);
                case "left": return performGlobalAction(GLOBAL_ACTION_DPAD_LEFT);
                case "right": return performGlobalAction(GLOBAL_ACTION_DPAD_RIGHT);
                default: return performGlobalAction(GLOBAL_ACTION_DPAD_CENTER);
            }
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo now = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (now == null) return false;
        if (k.equals("ok")) return now.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        int dir = k.equals("up") ? View.FOCUS_UP : k.equals("down") ? View.FOCUS_DOWN
                : k.equals("left") ? View.FOCUS_LEFT : View.FOCUS_RIGHT;
        AccessibilityNodeInfo next = now.focusSearch(dir);
        return next != null && next.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
    }
}
