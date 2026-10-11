package dev.mihir.dawnpatrol;

import android.Manifest;
import android.app.Activity;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.TimePicker;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Setup + field journal. Everything runs on-device; the manifest has no
 * INTERNET permission, so nothing here can talk to a server even by accident.
 */
public class MainActivity extends Activity {

    private Store store;
    private TextView armedText, journalText, streakText, statusText;
    private Button armBtn;
    private Spinner sensitivity;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            store = new Store(this);
            setContentView(buildUi());
            refresh();
            maybeShowCrashLog();
        } catch (Throwable t) {
            android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this);
            b.setTitle("Dawn Patrol hit a bug on launch")
                    .setMessage(String.valueOf(t))
                    .setPositiveButton("Close", (d, w) -> finish())
                    .show();
        }
    }

    private void maybeShowCrashLog() {
        String crash = App.readCrashLog(this);
        if (crash == null) return;
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this);
        b.setTitle("Previous session crashed — screenshot this")
                .setMessage(crash.length() > 3500 ? crash.substring(crash.length() - 3500) : crash)
                .setPositiveButton("OK", (d, w) -> { })
                .show();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B1020);
        int pad = dp(20);
        root.setPadding(pad, dp(40), pad, pad);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text(root, "🌅 Dawn Patrol", 28f, true);
        TextView tag = text(root, "the alarm that only stops for real birdsong", 14f, false);
        tag.setPadding(0, 0, 0, dp(16));

        armedText = text(root, "", 15f, true);
        armedText.setPadding(0, 0, 0, dp(8));

        Button timeBtn = button(root, "Wake time", v -> pickTime());
        timeBtn.setTag("timeBtn");

        TextView sensLabel = text(root, "Birdsong proof strictness", 13f, false);
        sensitivity = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, Store.SENSITIVITIES);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sensitivity.setAdapter(ad);
        sensitivity.setSelection(store.sensitivityIndex());
        sensitivity.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                store.setSensitivityIndex(pos);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> p) {
            }
        });
        root.addView(sensitivity, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        armBtn = button(root, "Arm alarm", v -> toggleArm());
        Button testBtn = button(root, "Test the flow now", v -> {
            Intent i = new Intent(this, RingActivity.class);
            i.putExtra("test", true);
            startActivity(i);
        });
        testBtn.setPadding(0, 0, 0, 0);

        statusText = text(root, "", 12f, false);
        statusText.setPadding(0, dp(10), 0, dp(10));

        text(root, "── Field journal ──", 16f, true);
        streakText = text(root, "", 13f, false);
        journalText = text(root, "", 13f, false);

        TextView foot = text(root,
                "YAMNet (Apache-2.0, Google Research) runs on-device via ONNX Runtime.\n"
                        + "No INTERNET permission — audio never leaves your phone.\n"
                        + "github.com/Mihir-Rabari/dawn-patrol-android", 11f, false);
        foot.setPadding(0, dp(24), 0, 0);
        return scroll;
    }

    private void pickTime() {
        final int h = store.alarmHour();
        final int m = store.alarmMinute();
        new TimePickerDialog(this, new TimePickerDialog.OnTimeSetListener() {
            @Override
            public void onTimeSet(TimePicker view, int hourOfDay, int minute) {
                store.setAlarmTime(hourOfDay, minute);
                refresh();
            }
        }, h, m, false).show();
    }

    private void toggleArm() {
        if (store.armed()) {
            AlarmReceiver.cancel(this, store);
            setStatus("Disarmed.");
        } else {
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 9);
                // user can also arm first and grant later; proceed either way
            }
            long triggerAt = AlarmReceiver.schedule(this, store);
            SimpleDateFormat f = new SimpleDateFormat("EEE d MMM, HH:mm", Locale.US);
            setStatus((AlarmReceiver.exactAllowed(this)
                    ? "Armed (exact) for "
                    : "Armed (inexact — allow exact alarms in Settings) for ")
                    + f.format(new Date(triggerAt)));
        }
        refresh();
    }

    private void setStatus(String s) {
        statusText.setText(s);
    }

    private void refresh() {
        if (store.armed()) {
            SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.US);
            armedText.setText("Armed for " + f.format(new Date(store.triggerAt())));
            armBtn.setText("Disarm");
        } else {
            armedText.setText("Not armed");
            armBtn.setText("Arm alarm");
        }
        StringBuilder jb = new StringBuilder();
        List<Store.Entry> entries = store.recentEntries(10);
        SimpleDateFormat dayFmt = new SimpleDateFormat("EEE d MMM · HH:mm", Locale.US);
        for (Store.Entry e : entries) {
            jb.append(dayFmt.format(new Date(e.ts)))
                    .append(" · ").append("live".equals(e.mode) ? "live" : "demo")
                    .append(e.conceded ? " · conceded" : " · " + e.birdSeconds + "s of birdsong")
                    .append('\n');
        }
        journalText.setText(entries.isEmpty() ? "No mornings logged yet." : jb.toString().trim());
        streakText.setText("Grass streak: " + store.streak()
                + " · mornings logged: " + entries.size());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private TextView text(LinearLayout parent, String s, float size, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTypeface(bold ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
        t.setTextColor(0xFFE8ECF8);
        t.setPadding(0, dp(4), 0, dp(4));
        parent.addView(t, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    private Button button(LinearLayout parent, String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextColor(0xFF1B1205);
        b.setBackgroundColor(0xFFFFB454);
        b.setPadding(dp(10), dp(10), dp(10), dp(10));
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(l);
        parent.addView(b, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
