package dev.mihir.dawnpatrol;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Native storage: SQLite journal + SharedPreferences settings.
 * Every morning is a row; your data never leaves the device.
 */
public class Store extends SQLiteOpenHelper {

    public static final String[] SENSITIVITIES = {"Relaxed", "Normal", "Strict"};
    public static final float[] THRESHOLDS = {0.10f, 0.15f, 0.25f};
    public static final float[] REQUIRE_SECONDS = {4f, 6f, 8f};

    public static class Entry {
        public long ts;
        public String mode;      // "live" | "demo"
        public int dismissedAfterSec;
        public float birdSeconds;
        public boolean conceded;
        public String censusJson;
        public String dayKey;
    }

    private final SharedPreferences prefs;

    public Store(Context ctx) {
        super(ctx, "dawnpatrol.db", null, 1);
        prefs = ctx.getSharedPreferences("dawn", Context.MODE_PRIVATE);
    }

    /* ---------------- settings ---------------- */

    public int sensitivityIndex() {
        return prefs.getInt("sensitivity", 1); // Normal default
    }

    public void setSensitivityIndex(int i) {
        prefs.edit().putInt("sensitivity", i).apply();
    }

    public float threshold() {
        return THRESHOLDS[sensitivityIndex()];
    }

    public float requireSeconds() {
        return REQUIRE_SECONDS[sensitivityIndex()];
    }

    public int alarmHour() {
        return prefs.getInt("alarmHour", 6);
    }

    public int alarmMinute() {
        return prefs.getInt("alarmMinute", 30);
    }

    public void setAlarmTime(int h, int m) {
        prefs.edit().putInt("alarmHour", h).putInt("alarmMinute", m).apply();
    }

    public boolean armed() {
        return prefs.getBoolean("armed", false);
    }

    public long triggerAt() {
        return prefs.getLong("triggerAt", 0);
    }

    public void arm(long triggerAt) {
        prefs.edit().putBoolean("armed", true).putLong("triggerAt", triggerAt).apply();
    }

    public void disarm() {
        prefs.edit().putBoolean("armed", false).putLong("triggerAt", 0).apply();
    }

    /* ---------------- journal ---------------- */

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE entries ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "ts INTEGER NOT NULL, "
                + "dayKey TEXT NOT NULL, "
                + "mode TEXT NOT NULL, "
                + "dismissedAfterSec INTEGER NOT NULL, "
                + "birdSeconds REAL NOT NULL, "
                + "conceded INTEGER NOT NULL, "
                + "censusJson TEXT NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        db.execSQL("DROP TABLE entries");
        onCreate(db);
    }

    public void saveEntry(Entry e) {
        ContentValues cv = new ContentValues();
        cv.put("ts", e.ts);
        cv.put("dayKey", e.dayKey);
        cv.put("mode", e.mode);
        cv.put("dismissedAfterSec", e.dismissedAfterSec);
        cv.put("birdSeconds", e.birdSeconds);
        cv.put("conceded", e.conceded ? 1 : 0);
        cv.put("censusJson", e.censusJson == null ? "{}" : e.censusJson);
        getWritableDatabase().insert("entries", null, cv);
    }

    public List<Entry> recentEntries(int limit) {
        List<Entry> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query(
                "entries", null, null, null, null, null, "ts DESC", String.valueOf(limit));
        SimpleDateFormat dayFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        while (c.moveToNext()) {
            Entry e = new Entry();
            e.ts = c.getLong(c.getColumnIndexOrThrow("ts"));
            e.dayKey = dayFmt.format(new Date(e.ts));
            e.mode = c.getString(c.getColumnIndexOrThrow("mode"));
            e.dismissedAfterSec = c.getInt(c.getColumnIndexOrThrow("dismissedAfterSec"));
            e.birdSeconds = c.getFloat(c.getColumnIndexOrThrow("birdSeconds"));
            e.conceded = c.getInt(c.getColumnIndexOrThrow("conceded")) == 1;
            e.censusJson = c.getString(c.getColumnIndexOrThrow("censusJson"));
            out.add(e);
        }
        c.close();
        return out;
    }

    /** consecutive days with a non-conceded morning, counting today backwards */
    public int streak() {
        Set<String> days = new HashSet<>();
        for (Entry e : recentEntries(3650)) {
            if (!e.conceded) days.add(e.dayKey);
        }
        int n = 0;
        SimpleDateFormat dayFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        Date d = new Date();
        for (int i = 0; i < 3650; i++) {
            if (days.contains(dayFmt.format(d))) {
                n++;
            } else if (n > 0) {
                break;
            }
            d = new Date(d.getTime() - 86400000L);
        }
        return n;
    }

    public static JSONObject censusToJson(java.util.Map<String, float[]> census)
            throws Exception {
        JSONObject o = new JSONObject();
        for (java.util.Map.Entry<String, float[]> en : census.entrySet()) {
            JSONObject c = new JSONObject();
            c.put("max", en.getValue()[0]);
            c.put("hits", (int) en.getValue()[1]);
            o.put(en.getKey(), c);
        }
        return o;
    }
}
