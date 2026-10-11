package dev.mihir.dawnpatrol;

import android.content.Context;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OnnxValue;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * YAMNet on-device via ONNX Runtime — a 1:1 port of the verified web engine
 * (js/engine.js + tools/tune_scoring.py). Same model file, same bird index,
 * same 0.96 s frame hop, same dedup cursor. The trainer/probe is the spec.
 */
public class Classifier {

    public static final int SR = 16000;
    public static final float FRAME_SECONDS = 0.96f;

    public static class Frame {
        public float t;
        public float evidence;
        public String bestName;
        public float bestScore;
        public String top3;
    }

    private OrtEnvironment env;
    private OrtSession session;
    private String[] classNames = new String[521];
    private int[] birdIdx;
    private float lastFrameTime = -Float.MAX_VALUE;
    private volatile boolean ready = false;

    public boolean isReady() {
        return ready;
    }

    /** copies the model from assets to filesDir and creates the session.
     * Call on a background thread. */
    public void init(Context ctx) throws Exception {
        if (ready) return;
        File modelFile = staged(ctx, "yamnet.onnx");
        File birdFile = staged(ctx, "bird-classes.json");
        File csvFile = staged(ctx, "yamnet_class_map.csv");

        // class names: index,mid,display (display may contain commas)
        InputStream in = ctx.getAssets().open("yamnet_class_map.csv");
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        String headerLine = r.readLine();
        String line;
        while ((line = r.readLine()) != null) {
            if (line.isEmpty()) continue;
            String[] p = line.split(",", 3);
            try {
                int idx = Integer.parseInt(p[0].trim());
                String disp = p.length > 2 ? p[2].trim() : "";
                if (disp.length() >= 2 && disp.charAt(0) == '"') {
                    disp = disp.substring(1, disp.length() >= 2 && disp.charAt(disp.length() - 1) == '"' ? disp.length() - 1 : disp.length());
                }
                classNames[idx] = disp;
            } catch (Exception ignored) {
            }
        }
        r.close();

        // bird index
        JSONArray arr = new JSONArray(readFile(birdFile));
        birdIdx = new int[arr.length()];
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            birdIdx[i] = o.getInt("index");
        }

        env = OrtEnvironment.getEnvironment();
        session = env.createSession(modelFile.getAbsolutePath(), new OrtSession.SessionOptions());
        ready = true;
    }

    private static File staged(Context ctx, String name) throws Exception {
        File out = new File(ctx.getFilesDir(), name);
        if (!out.exists() || out.length() == 0) {
            InputStream in = ctx.getAssets().open(name);
            FileOutputStream fo = new FileOutputStream(out);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
            fo.close();
            in.close();
        }
        return out;
    }

    private static String readFile(File f) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(
                new java.io.FileInputStream(f), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String l;
        while ((l = r.readLine()) != null) sb.append(l);
        r.close();
        return sb.toString();
    }

    /**
     * Classify a mono 16 kHz window (raw waveform input — the frontend is
     * inside the ONNX, verified in-browser + in-python). Returns ALL frames
     * and mutates the dedup cursor, same semantics as js/engine.js.
     */
    public List<Frame> classify(float[] samples, float windowStart) throws Exception {
        if (!ready) throw new IllegalStateException("classifier not ready");
        long n = samples.length;
        OnnxTensor t = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(samples, 0, samples.length), new long[]{n});
        Map<String, OnnxTensor> feeds = new HashMap<>();
        String inputName = session.getInputNames().iterator().next();
        feeds.put(inputName, t);
        List<Frame> frames = new ArrayList<>();
        try {
            OrtSession.Result res = session.run(feeds);
            try {
                Object v = res.get(0).getValue();
                float[][] scores = null;
                float[][][] scores3 = null;
                if (v instanceof float[][]) {
                    scores = (float[][]) v;
                } else if (v instanceof float[][][]) {
                    scores3 = (float[][][]) v;
                }
                if (scores3 != null && scores3.length > 0) {
                    scores = scores3[0];
                }
                if (scores == null) return frames;
                for (int f = 0; f < scores.length; f++) {
                    float[] row = scores[f];
                    Frame fr = new Frame();
                    float ev = 0;
                    int bestI = -1;
                    float bestV = 0;
                    for (int bi : birdIdx) {
                        float val = row[bi];
                        if (val > ev) ev = val;
                        if (val > bestV) {
                            bestV = val;
                            bestI = bi;
                        }
                    }
                    fr.evidence = ev;
                    fr.bestName = bestI >= 0 ? classNames[bestI] : "?";
                    fr.bestScore = bestV;
                    fr.top3 = top3(row);
                    fr.t = windowStart + (f + 0.5f) * FRAME_SECONDS;
                    frames.add(fr);
                }
            } finally {
                res.close();
            }
        } finally {
            t.close();
        }
        // dedup cursor (overlapping windows): advance and report
        for (Frame fr : frames) {
            if (fr.t > lastFrameTime) {
                lastFrameTime = fr.t;
            }
        }
        return frames;
    }

    /** frames strictly newer than the cursor at call time */
    public List<Frame> newSinceCursor(float cursorBefore, List<Frame> frames) {
        List<Frame> out = new ArrayList<>();
        for (Frame fr : frames) {
            if (fr.t > cursorBefore) out.add(fr);
        }
        return out;
    }

    public float cursor() {
        return lastFrameTime;
    }

    public void resetCursor() {
        lastFrameTime = -Float.MAX_VALUE;
    }

    private String top3(float[] row) {
        int i1 = -1, i2 = -1, i3 = -1;
        float v1 = 0, v2 = 0, v3 = 0;
        for (int i = 0; i < row.length; i++) {
            float x = row[i];
            if (x > v1) {
                v3 = v2; i3 = i2;
                v2 = v1; i2 = i1;
                v1 = x; i1 = i;
            } else if (x > v2) {
                v3 = v2; i3 = i2;
                v2 = x; i2 = i;
            } else if (x > v3) {
                v3 = x; i3 = i;
            }
        }
        StringBuilder sb = new StringBuilder();
        if (i1 >= 0 && v1 > 0.08f) sb.append(className(i1)).append(' ').append(pct(v1));
        if (i2 >= 0 && v2 > 0.08f) sb.append(" · ").append(className(i2)).append(' ').append(pct(v2));
        if (i3 >= 0 && v3 > 0.08f) sb.append(" · ").append(className(i3)).append(' ').append(pct(v3));
        return sb.toString();
    }

    private String className(int i) {
        return i >= 0 && i < classNames.length && classNames[i] != null ? classNames[i] : "?";
    }

    private static String pct(float v) {
        return Math.round(v * 100) + "%";
    }
}
