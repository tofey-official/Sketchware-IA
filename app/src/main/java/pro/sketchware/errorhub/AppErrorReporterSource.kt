package pro.sketchware.errorhub

import pro.sketchware.supabase.SupabaseClientSource

/**
 * Source of the ErrorReporter.java file added to every app built by Sumer Studio.
 *
 * The generated class captures, inside the USER'S built app (not the IDE):
 *  1. Uncaught Java crashes  -> sent as error_type "crash".
 *  2. The app's own logcat ERROR stream -> sent as "runtime" (or "flutter" when the
 *     flutter engine tag is present), which is how Dart/Flutter engine failures become
 *     visible without adb.
 *  3. Manual reports via ErrorReporter.log(...)/logError(...) -> "runtime".
 *
 * Everything is fail-silent: no network, no crash loop, no dependency beyond
 * java.net.HttpURLConnection, and it compiles at Java 1.7 level.
 */
object AppErrorReporterSource {

    /** Endpoint + key of the user's deployed Error Hub. */
    const val DEFAULT_ENDPOINT = "https://camillecyrm.serv00.net/errorhub/api.php?action=create"
    const val DEFAULT_API_KEY = "sumer_d77c474a5263587275765f401cead446"

    @JvmStatic
    @JvmOverloads
    fun generate(
        packageName: String,
        endpoint: String = DEFAULT_ENDPOINT,
        apiKey: String = DEFAULT_API_KEY
    ): String = TEMPLATE
        .replace("__PACKAGE__", packageName)
        .replace("__ENDPOINT__", SupabaseClientSource.javaString(endpoint))
        .replace("__API_KEY__", SupabaseClientSource.javaString(apiKey))

    private val TEMPLATE = """package __PACKAGE__;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Sumer Error Hub - يرسل أخطاء وكراشات هذا التطبيق إلى لوحة Sumer Error Hub
 * حتى يعرف المطوّر سبب الكراش بدون حاسوب. تم توليده تلقائياً بواسطة Sumer Studio.
 */
public class ErrorReporter {

    // ================= إعدادات Error Hub =================
    private static final String ENDPOINT = __ENDPOINT__;
    private static final String API_KEY = __API_KEY__;
    private static final boolean ENABLED = true;
    // =====================================================

    private static final int MAX_BATCH_LINES = 200;
    private static final long FLUSH_INTERVAL_MS = 8000L;
    private static final int MAX_REPORTS_PER_HOUR = 40;

    private static final Object LOCK = new Object();
    private static boolean initialized;
    private static String appName = "";
    private static String appPackage = "";
    private static String appVersion = "";
    private static String deviceInfo = "";
    private static int sentThisHour = 0;
    private static long hourStart = 0L;
    private static Thread.UncaughtExceptionHandler previousHandler;

    private static final BlockingQueue<String> pendingLines = new LinkedBlockingQueue<String>();

    /** يُستدعى تلقائياً في onCreate لكل Activity مولّدة. */
    public static void initialize(Context context) {
        if (!ENABLED || context == null) {
            return;
        }
        synchronized (LOCK) {
            if (initialized) {
                return;
            }
            initialized = true;
        }
        try {
            appPackage = context.getPackageName();
            PackageManager pm = context.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(appPackage, 0);
            CharSequence label = pm.getApplicationLabel(ai);
            appName = label != null ? label.toString() : appPackage;
            PackageInfo pi = pm.getPackageInfo(appPackage, 0);
            appVersion = pi.versionName != null ? pi.versionName : "";
        } catch (Throwable t) {
            appName = appPackage;
        }
        deviceInfo = Build.MODEL + " - Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")";

        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                try {
                    String title = "Crash in " + thread.getName() + ": " + summary(throwable);
                    Thread reporter = post("crash", title, stackTrace(throwable));
                    if (reporter != null) {
                        // امنح الطلب فرصة قصيرة ليصل قبل موت العملية
                        reporter.join(1500L);
                    }
                } catch (Throwable ignored) {
                }
                if (previousHandler != null) {
                    previousHandler.uncaughtException(thread, throwable);
                }
            }
        });

        startLogcatWatcher();
    }

    /** تسجيل رسالة يدوياً (مفيدة من البلوكات عند try/catch). */
    public static void log(String message) {
        post("runtime", message, "");
    }

    /** تسجيل خطأ مع كامل تفاصيل الـ Exception. */
    public static void logError(String tag, Throwable t) {
        post("runtime", tag + ": " + summary(t), stackTrace(t));
    }

    private static void startLogcatWatcher() {
        Thread watcher = new Thread(new Runnable() {
            @Override
            public void run() {
                watchLogcat();
            }
        }, "ErrorHub-logcat");
        watcher.setDaemon(true);
        watcher.start();

        Thread sender = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(FLUSH_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                    try {
                        flush();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "ErrorHub-sender");
        sender.setDaemon(true);
        sender.start();
    }

    /** يقرأ سجل الأخطاء E/ الخاص بهذا التطبيق فقط (يشمل أخطاء محرك Flutter/Dart). */
    private static void watchLogcat() {
        BufferedReader reader = null;
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"logcat", "-v", "brief", "*:E"});
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()), 8192);
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() < 20) {
                    continue;
                }
                if (line.contains("EGL_emulation") || line.contains("OpenGLRenderer")
                        || line.contains("ViewRootImpl") || line.contains("chatty")
                        || line.contains("ErrorReporter")) {
                    continue;
                }
                if (pendingLines.size() < MAX_BATCH_LINES) {
                    pendingLines.offer(line);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignored2) {
                }
            }
        }
    }

    private static void flush() {
        if (pendingLines.isEmpty()) {
            return;
        }
        List<String> batch = new ArrayList<String>();
        pendingLines.drainTo(batch, MAX_BATCH_LINES);
        if (batch.isEmpty()) {
            return;
        }
        StringBuffer all = new StringBuffer();
        for (int i = 0; i < batch.size(); i++) {
            all.append(batch.get(i)).append('\n');
        }
        String first = batch.get(0);
        String type = (first.contains("flutter") || first.contains("Flutter")) ? "flutter" : "runtime";
        post(type, stripLogPrefix(first), all.toString());
    }

    private static String stripLogPrefix(String line) {
        int idx = line.indexOf("]: ");
        if (idx >= 0 && idx + 3 <= line.length()) {
            return line.substring(idx + 3);
        }
        return line;
    }

    private static boolean allowSend() {
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            if (now - hourStart > 3600000L) {
                hourStart = now;
                sentThisHour = 0;
            }
            if (sentThisHour >= MAX_REPORTS_PER_HOUR) {
                return false;
            }
            sentThisHour++;
            return true;
        }
    }

    private static Thread post(final String type, final String message, final String trace) {
        if (!ENABLED || !allowSend()) {
            return null;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    httpPost(type, message, trace);
                } catch (Throwable ignored) {
                }
            }
        }, "ErrorHub-post");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void httpPost(String type, String message, String trace) throws Exception {
        String body = "{\"project_name\":" + q(appName)
                + ",\"project_id\":" + q(appPackage)
                + ",\"error_type\":" + q(type)
                + ",\"message\":" + q(message)
                + ",\"stack_trace\":" + q(trace)
                + ",\"device_info\":" + q(deviceInfo)
                + ",\"app_version\":" + q("v" + appVersion)
                + ",\"source\":\"sumer-generated-app\"}";
        HttpURLConnection conn = null;
        try {
            URL url = new URL(ENDPOINT);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("X-API-Key", API_KEY);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bytes.length);
            OutputStream os = conn.getOutputStream();
            os.write(bytes);
            os.flush();
            os.close();
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                Log.w("ErrorReporter", "Error Hub responded " + code);
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String summary(Throwable t) {
        String m = t.getMessage();
        return t.getClass().getName() + (m != null ? (": " + m) : "");
    }

    private static String stackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }

    private static String q(String s) {
        if (s == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                sb.append("\\\"");
            } else if (c == '\\') {
                sb.append("\\\\");
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else if (c < 0x20) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
"""
}
