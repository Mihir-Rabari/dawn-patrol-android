package dev.mihir.dawnpatrol;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.annotation.SuppressLint;
import android.view.WindowManager;
import java.io.DataInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The ring screen: alarm plays (default alarm sound, escalating, vibrating)
 * until on-device YAMNet hears enough real birdsong. No snooze button exists.
 * Falls back to the bundled dawn-chorus sample (demo mode) when no mic is
 * available — same verification pipeline, clearly labeled.
 */
public class RingActivity extends Activity {

    private final Classifier classifier = new Classifier();
    private final AudioThread audio = new AudioThread();
    private Store store;
    private MediaPlayer player;
    private Vibrator vibrator;
    private Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread classifyThread;
    private Handler classify;
    private final Map<String, float[]> census = new HashMap<>(); // name -> [max, hits]
    private float birdSeconds = 0f;
    private long ringStart = 0;
    private String mode = "live";
    private boolean testMode = false;
    private boolean demoRunning = false;
    private float[] demoPcm = null;
    private int demoPos = 0;
    private boolean finished = false;

    // views
    private LinearLayout ringViews, listenViews, victoryViews;
    private TextView progressText, hearingText, victoryLine, censusBox, listenModeText;
    private ProgressBar progress;

    private static final int MIC_REQUEST = 7;
    private static final long CLASSIFY_INTERVAL_MS = 1500;
    private static final float WINDOW_SECONDS = 3f;
    private float alarmVol = 0.25f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            store = new Store(this);
            Window w = getWindow();
            w.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            setContentView(buildUi());
            testMode = getIntent().getBooleanExtra("test", false);
            startAlarmSound();
            ringStart = System.currentTimeMillis();
            // warm the model ~2 s after first frame (16 MB copy + session create)
            main.postDelayed(() -> {
                new Thread(() -> {
                    try {
                        classifier.init(getApplicationContext());
                    } catch (Exception ignored) {
                        // loop UI shows "warming" and the error surfaces on classify
                    }
                }, "dawn-model-init").start();
            }, 200);
        } catch (Throwable t) {
            crashExit(t);
        }
    }

    /* ---------------- UI (programmatic — zero resource indirection) ---------------- */

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, dp(40), pad, pad);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // --- state 1: ringing
        ringViews = column(root);
        root.addView(text(ringViews, "🌅 Time to touch grass", 26f, true));
        root.addView(text(ringViews,
                "The alarm will not stop until Dawn Patrol hears real birds.\n"
                        + "Take your phone outside and start listening.", 15f, false));
        Button listen = button(ringViews, "I'm outside — listen live", v -> tryStartListening());
        listen.setTextSize(19f);
        Button demo = button(ringViews, "No mic? Play the bundled dawn chorus", v -> startDemo());
        Button cancel = button(ringViews, "Cancel (test mode)", v -> finish());

        // --- state 2: listening
        listenViews = column(root);
        listenModeText = text(listenViews, "Live microphone", 13f, false);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        listenViews.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(18)));
        progressText = text(listenViews, "0.0 / 6.0 s of birdsong", 20f, true);
        hearingText = text(listenViews, "…", 13f, false);
        Button concede = button(listenViews, "Concede (logged honestly)", v -> concede());
        concede.setTextSize(13f);

        // --- state 3: victory
        victoryViews = column(root);
        root.addView(text(victoryViews, "🌄 Dawn verified", 26f, true));
        victoryLine = text(victoryViews, "", 15f, false);
        censusBox = text(victoryViews, "", 13f, false);
        Button done = button(victoryViews, "Done", v -> {
            finished = true;
            startActivity(new Intent(this, MainActivity.class));
            finish();
        });

        showOnly(ringViews);
        return scroll;
    }

    private LinearLayout column(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        parent.addView(c, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return c;
    }

    private TextView text(LinearLayout parent, String s, float size, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTypeface(bold ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
        t.setTextColor(0xFFE8ECF8);
        t.setPadding(0, dp(6), 0, dp(6));
        parent.addView(t, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    private Button button(LinearLayout parent, String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextColor(0xFF1B1205);
        b.setBackgroundColor(0xFFFFB454);
        b.setOnClickListener(l);
        b.setPadding(dp(12), dp(12), dp(12), dp(12));
        parent.addView(b, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    private void showOnly(LinearLayout visible) {
        ringViews.setVisibility(visible == ringViews ? View.VISIBLE : View.GONE);
        listenViews.setVisibility(visible == listenViews ? View.VISIBLE : View.GONE);
        victoryViews.setVisibility(visible == victoryViews ? View.VISIBLE : View.GONE);
    }

    /* ---------------- alarm sound ---------------- */

    private void startAlarmSound() {
        try {
            android.net.Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            player = new MediaPlayer();
            player.setAudioStreamType(AudioManager.STREAM_ALARM);
            player.setDataSource(this, uri);
            player.setLooping(true);
            player.setVolume(0.25f, 0.25f);
            player.prepare();
            player.start();
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (player != null && player.isPlaying()) {
                        try {
                            alarmVol = Math.min(1f, alarmVol + 0.12f);
                            player.setVolume(alarmVol, alarmVol);
                        } catch (Exception ignored) {
                        }
                        main.postDelayed(this, 8000);
                    }
                }
            }, 8000);
        } catch (Exception ignored) {
            // silent phones still wake the screen + vibrate
        }
        try {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                long[] pattern = {0, 400, 200, 400, 600};
                if (Build.VERSION.SDK_INT >= 26) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
                } else {
                    vibrator.vibrate(pattern, 0);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void stopAlarmSound() {
        try {
            if (player != null) {
                player.stop();
                player.release();
            }
        } catch (Exception ignored) {
        }
        player = null;
        try {
            if (vibrator != null) vibrator.cancel();
        } catch (Exception ignored) {
        }
    }

    /* ---------------- listening: live mic ---------------- */

    private void tryStartListening() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_REQUEST);
            return;
        }
        beginLiveListening();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] perms, int[] results) {
        if (requestCode != MIC_REQUEST) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            beginLiveListening();
        } else {
            listenModeText.setText("Microphone permission denied — demo mode uses the same verification.");
            startDemo();
        }
    }

    private void beginLiveListening() {
        mode = "live";
        listenModeText.setText("Live microphone · on-device YAMNet · no network");
        beginListeningCommon();
    }

    private void beginListeningCommon() {
        showOnly(listenViews);
        birdSeconds = 0f;
        census.clear();
        classifier.resetCursor();
        acquireCpuLock();
        classifyThread = new HandlerThread("dawn-classify");
        classifyThread.start();
        classify = new Handler(classifyThread.getLooper());
        final float threshold = store.threshold();
        final float required = store.requireSeconds();
        progressText.setText("0.0 / " + required + " s of birdsong");

        if ("live".equals(mode)) {
            try {
                audio.start();
            } catch (Exception e) {
                listenModeText.setText("Microphone unavailable (" + e.getClass().getSimpleName()
                        + ") — switching to demo.");
                mode = "demo";
                if (!prepareDemoPcm()) return;
            }
        } else {
            if (!prepareDemoPcm()) return;
        }

        classify.post(new Runnable() {
            @Override
            public void run() {
                if (finished || classify == null) return;
                try {
                    float windowStart;
                    float[] window;
                    if ("live".equals(mode) && audio.isRunning()) {
                        float elapsed = audio.elapsedSeconds();
                        windowStart = Math.max(0f, elapsed - WINDOW_SECONDS);
                        window = audio.latest(WINDOW_SECONDS);
                    } else {
                        int winLen = Classifier.SR * (int) WINDOW_SECONDS;
                        if (demoPos >= demoPcm.length) {
                            main.post(() -> listenModeText.setText(
                                    "Sample finished before proof of dawn. Try again or concede."));
                            return;
                        }
                        int end = Math.min(demoPos + winLen, demoPcm.length);
                        window = java.util.Arrays.copyOfRange(demoPcm, demoPos, end);
                        windowStart = demoPos / (float) Classifier.SR;
                        demoPos = end;
                    }
                    if (window.length >= Classifier.SR / 2 && classifier.isReady()) {
                        float cursorBefore = classifier.cursor();
                        List<Classifier.Frame> frames = classifier.classify(window, windowStart);
                        for (Classifier.Frame fr : frames) {
                            if (fr.t > cursorBefore) {
                                if (fr.evidence >= threshold) {
                                    birdSeconds += Classifier.FRAME_SECONDS;
                                }
                                float[] c = census.get(fr.bestName);
                                if (c == null) {
                                    c = new float[]{0f, 0f};
                                    census.put(fr.bestName, c);
                                }
                                c[0] = Math.max(c[0], fr.evidence);
                                c[1] += fr.evidence >= threshold ? 1f : 0f;
                            }
                        }
                        if (!frames.isEmpty()) {
                            final Classifier.Frame last =
                                    frames.get(frames.size() - 1);
                            final float bs = birdSeconds;
                            main.post(() -> {
                                if (finished) return;
                                hearingText.setText(last.top3.isEmpty() ? "…" : last.top3);
                                progressText.setText(bs + " / " + required + " s of birdsong");
                                progress.setProgress((int) Math.min(100,
                                        (bs / required) * 100));
                            });
                        }
                        if (birdSeconds >= required) {
                            main.post(() -> victory());
                            return;
                        }
                    } else if (!classifier.isReady()) {
                        main.post(() -> listenModeText.setText(
                                "Loading model on-device… (" + (classifier.isReady() ? "ready" : "warming") + ")"));
                    }
                } catch (final Exception e) {
                    main.post(() -> listenModeText.setText("Inference error: " + e.getMessage()));
                }
                if (classify != null) {
                    classify.postDelayed(this, CLASSIFY_INTERVAL_MS);
                }
            }
        });
    }

    /* ---------------- listening: demo ---------------- */

    private boolean prepareDemoPcm() {
        try {
            if (demoPcm == null) demoPcm = readWavAsset("dawn-chorus-demo.wav");
            demoPos = 0;
            classifier.resetCursor();
            mode = "demo";
            return true;
        } catch (Exception e) {
            listenModeText.setText("Demo asset failed to load: " + e.getMessage());
            return false;
        }
    }

    private void startDemo() {
        mode = "demo";
        listenModeText.setText("Demo: bundled dawn chorus (CC BY-SA — same verification pipeline)");
        beginListeningCommon();
    }

    private float[] readWavAsset(String name) throws Exception {
        InputStream in = getAssets().open(name);
        DataInputStream d = new DataInputStream(in);
        byte[] header4 = new byte[4];
        d.readFully(header4); // "RIFF"
        d.readInt();          // size
        d.readFully(header4); // "WAVE"
        // walk chunks until 'data'
        boolean found = false;
        while (!found) {
            d.readFully(header4);
            String chunkId = new String(header4, "US-ASCII");
            int size = Integer.reverseBytes(d.readInt());
            if ("data".equals(chunkId)) {
                found = true;
                int n = size / 2;
                float[] out = new float[n];
                for (int i = 0; i < n; i++) {
                    out[i] = Short.reverseBytes(d.readShort()) / 32768f;
                }
                d.close();
                return out;
            }
            long skipped = d.skip(size + (size % 2));
            if (skipped <= 0) break;
        }
        d.close();
        throw new IllegalStateException("no data chunk in " + name);
    }

    /* ---------------- outcomes ---------------- */

    private void victory() {
        if (finished) return;
        finished = true;
        stopAlarmSound();
        stopClassify();
        audio.stop();
        releaseCpuLock();

        long elapsed = (System.currentTimeMillis() - ringStart) / 1000;
        victoryLine.setText("live".equals(mode)
                ? "Alarm off after " + (elapsed / 60) + "m " + (elapsed % 60)
                        + "s — the real world vouched for you."
                : "Alarm off (demo mode) — " + birdSeconds + "s of verified birdsong.");

        StringBuilder sb = new StringBuilder();
        sb.append("Dawn census\n\n");
        int shown = 0;
        for (Map.Entry<String, float[]> e : census.entrySet()) {
            if (e.getValue()[1] <= 0) continue;
            sb.append(e.getKey()).append("  ")
                    .append((int) e.getValue()[1]).append("× · peak ")
                    .append(Math.round(e.getValue()[0] * 100)).append("%\n");
            shown++;
            if (shown >= 6) break;
        }
        if (shown == 0) sb.append("(no bird classes crossed the threshold)");
        sb.append("\nGrass streak: ").append(store.streak());
        censusBox.setText(sb.toString());

        Store.Entry entry = new Store.Entry();
        entry.ts = System.currentTimeMillis();
        entry.dayKey = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(new java.util.Date(entry.ts));
        entry.mode = mode;
        entry.dismissedAfterSec = (int) elapsed;
        entry.birdSeconds = birdSeconds;
        entry.conceded = false;
        try {
            entry.censusJson = Store.censusToJson(census).toString();
        } catch (Exception ignored) {
            entry.censusJson = "{}";
        }
        store.saveEntry(entry);
        if (!testMode) {
            store.disarm();
        }
        showOnly(victoryViews);
    }

    private void concede() {
        if (finished) return;
        finished = true;
        stopAlarmSound();
        stopClassify();
        audio.stop();
        releaseCpuLock();
        long elapsed = (System.currentTimeMillis() - ringStart) / 1000;
        Store.Entry entry = new Store.Entry();
        entry.ts = System.currentTimeMillis();
        entry.dayKey = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(new java.util.Date(entry.ts));
        entry.mode = mode;
        entry.dismissedAfterSec = (int) elapsed;
        entry.birdSeconds = 0;
        entry.conceded = true;
        entry.censusJson = "{}";
        store.saveEntry(entry);
        if (!testMode) {
            store.disarm();
        }
        finished = true;
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    private void stopClassify() {
        if (classifyThread != null) {
            classifyThread.quitSafely();
            classifyThread = null;
            classify = null;
        }
    }

    private PowerManager.WakeLock cpuLock;

    private void acquireCpuLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dawnpatrol:listen");
            cpuLock.acquire(10 * 60 * 1000L);
        } catch (Exception ignored) {
        }
    }

    private void releaseCpuLock() {
        try {
            if (cpuLock != null && cpuLock.isHeld()) cpuLock.release();
        } catch (Exception ignored) {
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void crashExit(Throwable t) {
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this);
        b.setTitle("Dawn Patrol hit a bug")
                .setMessage(String.valueOf(t))
                .setPositiveButton("Close", (d, w) -> finish())
                .show();
    }

    @Override
    protected void onDestroy() {
        finished = true;
        stopAlarmSound();
        stopClassify();
        audio.stop();
        releaseCpuLock();
        super.onDestroy();
    }
}
