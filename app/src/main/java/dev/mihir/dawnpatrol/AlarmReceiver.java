package dev.mihir.dawnpatrol;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import java.util.Calendar;

/**
 * Schedules the exact alarm and fires the ring screen. On modern Android the
 * alarm fires from here even with the screen off; the full-screen intent
 * launches RingActivity over the lockscreen.
 */
public class AlarmReceiver extends BroadcastReceiver {

    public static final String CHANNEL_ID = "dawn-alarms";
    public static final int REQUEST_CODE = 4242;

    /** schedule the next occurrence of the stored HH:MM; returns trigger millis */
    public static long schedule(Context ctx, Store store) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, store.alarmHour());
        cal.set(Calendar.MINUTE, store.alarmMinute());
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }
        long triggerAt = cal.getTimeInMillis();

        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = pendingIntent(ctx);
        boolean exact = true;
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            exact = false;
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        }
        store.arm(triggerAt);
        return exact ? triggerAt : triggerAt; // inexactness surfaced via canScheduleExactAlarms() in UI
    }

    public static boolean exactAllowed(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
    }

    public static void cancel(Context ctx, Store store) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        am.cancel(pendingIntent(ctx));
        store.disarm();
    }

    private static PendingIntent pendingIntent(Context ctx) {
        Intent i = new Intent(ctx, AlarmReceiver.class);
        return PendingIntent.getBroadcast(ctx, REQUEST_CODE, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Store store = new Store(context);
        if (!store.armed()) return;

        NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Dawn Patrol alarms",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("The alarm that only stops for real birdsong");
            nm.createNotificationChannel(ch);
        }

        Intent ring = new Intent(context, RingActivity.class);
        ring.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        // full-screen intent first (the sanctioned path over the lockscreen),
        // then a direct-start fallback wrapped in try/catch: a failed launch
        // must degrade to the notification, never crash.
        try {
            PendingIntent fsi = PendingIntent.getActivity(context, REQUEST_CODE + 1, ring,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            android.app.Notification.Builder nb;
            if (Build.VERSION.SDK_INT >= 26) {
                nb = new android.app.Notification.Builder(context, CHANNEL_ID);
            } else {
                nb = new android.app.Notification.Builder(context);
            }
            nb.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("Dawn Patrol")
                    .setContentText("Touch grass. The birds are waiting.")
                    .setPriority(android.app.Notification.PRIORITY_MAX)
                    .setCategory(android.app.Notification.CATEGORY_ALARM)
                    .setFullScreenIntent(fsi, true)
                    .setAutoCancel(true);
            nm.notify(4242, nb.build());
        } catch (Exception ignored) {
        }
        try {
            context.startActivity(ring);
        } catch (Exception ignored) {
        }
    }
}
