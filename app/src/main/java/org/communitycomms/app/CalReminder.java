package org.communitycomms.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** A calendar reminder going off: a notification (and a buzz if the entry asks for one). */
public final class CalReminder extends BroadcastReceiver {
    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context c, Intent i) {
        String title = i.getStringExtra("title");
        long start = i.getLongExtra("start", 0);
        boolean vibrate = i.getBooleanExtra("vibrate", true), team = i.getBooleanExtra("team", false), allDay = i.getBooleanExtra("allDay", false);
        String by = i.getStringExtra("by");
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        String ch = vibrate ? "cal-buzz" : "cal-quiet";
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel nc = new NotificationChannel(ch, vibrate ? "Calendar reminders (buzz)" : "Calendar reminders (quiet)",
                    NotificationManager.IMPORTANCE_HIGH);
            nc.enableVibration(vibrate);
            if (vibrate) nc.setVibrationPattern(new long[]{0, 400, 200, 400, 200, 400});
            nc.setSound(null, null);
            nm.createNotificationChannel(nc);
            b = new Notification.Builder(c, ch);
        } else {
            b = new Notification.Builder(c);
            if (vibrate) b.setVibrate(new long[]{0, 400, 200, 400, 200, 400});
        }
        String when = allDay ? new java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(new java.util.Date(start)) + " (all day)"
                : new java.text.SimpleDateFormat("EEE d MMM HH:mm", java.util.Locale.getDefault()).format(new java.util.Date(start));
        int fl = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent open = PendingIntent.getActivity(c, 15, new Intent(c, CalendarActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), fl);
        nm.notify(("cal" + i.getStringExtra("id")).hashCode(), b.setContentTitle((team ? "Team: " : "") + (title == null || title.length() == 0 ? "Calendar" : title))
                .setContentText(when + (team && by != null && by.length() > 0 ? " · from " + by : ""))
                .setSmallIcon(R.drawable.ic_stat_radio)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build());
        if (vibrate && Build.VERSION.SDK_INT < 26) {
            android.os.Vibrator v = (android.os.Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(new long[]{0, 400, 200, 400, 200, 400}, -1);
        }
    }
}
