package com.knz.telebisyon;

import android.service.notification.NotificationListenerService;

// Does nothing itself. Android only tells an app what's playing (and lets it pause it) while the app
// has a notification listener turned on, so Messages' /status and /media ask through this one.
// messages.sh turns it on with adb; nothing here reads notifications.
public class MediaListener extends NotificationListenerService {}
