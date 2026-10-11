package dev.mihir.dawnpatrol;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

/**
 * Raw mic capture at 16 kHz mono PCM16 (converted to float [-1,1]).
 * Uses the UNPROCESSED source when the device offers it — noiseSuppression
 * would happily eat distant birdsong; we want the world as it is.
 * Keeps an 8-second ring buffer; the classify loop reads latest(3s) windows.
 */
public class AudioThread {

    private AudioRecord record;
    private Thread thread;
    private final float[] ring = new float[Classifier.SR * 8];
    private int writePos = 0;
    private long totalFrames = 0;
    private volatile boolean running = false;

    public boolean isRunning() {
        return running;
    }

    public long elapsedFrames() {
        return totalFrames;
    }

    public float elapsedSeconds() {
        return totalFrames / (float) Classifier.SR;
    }

    @SuppressLint("MissingPermission") // caller must hold RECORD_AUDIO
    public synchronized void start() throws Exception {
        if (running) return;
        int min = AudioRecord.getMinBufferSize(Classifier.SR,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16);
        int bufSize = Math.max(min, 8192) * 2;

        record = trySource(MediaRecorder.AudioSource.UNPROCESSED, bufSize);
        if (record == null) record = trySource(MediaRecorder.AudioSource.MIC, bufSize);
        if (record == null) throw new IllegalStateException("AudioRecord init failed");

        running = true;
        record.startRecording();
        final short[] chunk = new short[2048];
        thread = new Thread(() -> {
            while (running) {
                int n = record.read(chunk, 0, chunk.length);
                if (n <= 0) {
                    if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                        break;
                    }
                    continue;
                }
                append(chunk, n);
            }
        }, "dawn-audio");
        thread.start();
    }

    private AudioRecord trySource(int source, int bufSize) {
        try {
            AudioRecord r = new AudioRecord(source, Classifier.SR,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16, bufSize);
            if (r.getState() == AudioRecord.STATE_INITIALIZED) return r;
            r.release();
        } catch (Exception ignored) {
        }
        return null;
    }

    private synchronized void append(short[] chunk, int n) {
        for (int i = 0; i < n; i++) {
            ring[writePos] = chunk[i] / 32768f;
            writePos = (writePos + 1) % ring.length;
        }
        totalFrames += n;
    }

    /** latest `seconds` of audio in chronological order */
    public synchronized float[] latest(float seconds) {
        int n = (int) Math.min(seconds * Classifier.SR, Math.min(totalFrames, ring.length));
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            int idx = (int) (((writePos - n + i) % ring.length + ring.length) % ring.length);
            out[i] = ring[idx];
        }
        return out;
    }

    public synchronized void stop() {
        running = false;
        if (thread != null) {
            try {
                thread.join(1000);
            } catch (InterruptedException ignored) {
            }
            thread = null;
        }
        if (record != null) {
            try {
                record.stop();
            } catch (Exception ignored) {
            }
            record.release();
            record = null;
        }
        totalFrames = 0;
        writePos = 0;
    }
}
