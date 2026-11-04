package pro.sketchware.errorhub;

import android.os.Build;

import com.google.gson.Gson;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import a.a.a.lC;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import pro.sketchware.BuildConfig;

/**
 * Sumer Error Hub — يرسل أخطاء البناء من داخل Sumer Studio (الـ IDE) إلى
 * لوحة المطور، حتى تُشاهد أخطاء مشاريع المستخدم (Java/Kotlin/Flutter) عن بعد.
 * <p>
 * كل شيء fail-silent: أي فشل شبكة أو استثناء يُتجاهل تماماً حتى لا يؤثر على البناء.
 */
public final class ErrorHub {

    private static final String ENDPOINT =
            "https://camillecyrm.serv00.net/errorhub/api.php?action=create";
    private static final String API_KEY =
            "sumer_d77c474a5263587275765f401cead446";

    /** لا ترسل أكثر من هذا العدد خلال ساعة (حماية من الحلقات). */
    private static final int MAX_REPORTS_PER_HOUR = 60;
    /** تجاهل تكرار نفس الخطأ خلال هذه المدة. */
    private static final long DEDUPE_WINDOW_MS = 90_000L;

    private static final Gson GSON = new Gson();
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
            1, 1, 30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(24),
            r -> {
                Thread t = new Thread(r, "ErrorHub-reporter");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardPolicy());

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build();

    private static final Object LOCK = new Object();
    private static String lastFingerprint = "";
    private static long lastSentAt = 0L;
    private static int sentThisHour = 0;
    private static long hourStart = 0L;

    private ErrorHub() {
    }

    /** يُستدعى عند فشل بناء مشروع (أخطاء javac/Kotlin/aapt2/Dart...). */
    public static void reportBuildError(String scId, String errorText) {
        report(scId, "build", firstLine(errorText), errorText);
    }

    /** تقرير عام من الـ IDE. */
    public static void report(String scId, String type, String message, String trace) {
        if (message == null || message.isEmpty()) {
            return;
        }
        try {
            if (!allowSend()) {
                return;
            }
            String fingerprint = type + "|" + message + "|" + (trace == null ? "" : trace.hashCode());
            synchronized (LOCK) {
                long now = System.currentTimeMillis();
                if (fingerprint.equals(lastFingerprint) && (now - lastSentAt) < DEDUPE_WINDOW_MS) {
                    return;
                }
                lastFingerprint = fingerprint;
                lastSentAt = now;
            }

            Map<String, Object> payload = new HashMap<>();
            payload.put("project_name", resolveProjectName(scId));
            payload.put("project_id", scId == null ? "" : scId);
            payload.put("error_type", type);
            payload.put("message", clip(message, 4000));
            payload.put("stack_trace", clip(trace == null ? "" : trace, 60000));
            payload.put("device_info", Build.MODEL + " - Android " + Build.VERSION.RELEASE);
            payload.put("app_version", "Sumer " + BuildConfig.VERSION_NAME);
            payload.put("source", "sumer-studio-ide");

            String body = GSON.toJson(payload);
            Request request = new Request.Builder()
                    .url(ENDPOINT)
                    .header("X-API-Key", API_KEY)
                    .post(RequestBody.create(body, JSON))
                    .build();

            EXECUTOR.execute(() -> {
                try (Response response = CLIENT.newCall(request).execute()) {
                    if (response.body() != null) {
                        response.close();
                    }
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static String resolveProjectName(String scId) {
        try {
            HashMap<String, Object> metadata = lC.b(scId);
            if (metadata != null) {
                Object name = metadata.get("my_app_name");
                if (name != null) {
                    return String.valueOf(name);
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        int nl = trimmed.indexOf('\n');
        String line = nl > 0 ? trimmed.substring(0, nl) : trimmed;
        return clip(line, 4000);
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static boolean allowSend() {
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            if (now - hourStart > 3_600_000L) {
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
}
