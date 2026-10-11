package dev.mihir.dawnpatrol;

import android.app.Application;
import android.content.Context;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Application entry. The only job here is making sure no crash ever happens
 * silently: any uncaught exception lands in files/crash-log.txt and the NEXT
 * launch shows it on screen ("screenshot this and send it") — no adb needed.
 */
public class App extends Application {

    public static final SimpleDateFormat STAMP =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    @Override
    public void onCreate() {
        super.onCreate();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                File f = new File(getFilesDir(), "crash-log.txt");
                FileWriter w = new FileWriter(f, true);
                w.write("== " + STAMP.format(new Date()) + " ==\n");
                StringWriter sw = new StringWriter();
                throwable.printStackTrace(new PrintWriter(sw));
                w.write(sw.toString());
                w.write("\n");
                w.close();
            } catch (Exception ignored) {
                // absolutely nothing may throw from here
            }
            android.os.Process.killProcess(android.os.Process.myPid());
        });
    }

    public static String readCrashLog(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), "crash-log.txt");
            if (!f.exists()) return null;
            byte[] b = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int read = in.read(b);
            in.close();
            String s = new String(b, 0, read, "UTF-8");
            f.delete();
            return s;
        } catch (Exception e) {
            return null;
        }
    }
}
