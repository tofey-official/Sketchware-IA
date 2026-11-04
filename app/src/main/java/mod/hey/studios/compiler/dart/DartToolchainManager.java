package mod.hey.studios.compiler.dart;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import a.a.a.KB;
import mod.jbk.util.LogUtil;
import pro.sketchware.SketchApplication;
import pro.sketchware.utility.FileUtil;

/**
 * Sumer Dart Toolchain — يدير أداة تجميع Dart الحقيقية على الجهاز.
 * <p>
 * الأداة تتكون من جزأين:
 * <ol>
 *   <li>ثنائية Dart VM (libsumerdart.so) — مضمّنة داخل APK الاستوديو نفسه
 *       (jniLibs) لأن أندرويد 10+ يمنع تنفيذ ملفات من مجلد البيانات.</li>
 *   <li>بيانات الأداة (flutter_patched_sdk + حزم Flutter + سائق CFE) —
 *       تُحمَّل مرة واحدة من GitHub Releases إلى filesDir/flutter_toolchain.</li>
 * </ol>
 * كل شيء fail-loud: عند فشل أي خطوة يُرمى استثناء برسالة واضحة يراها
 * المستخدم في شاشة البناء (ويُرسل تلقائياً إلى Error Hub).
 */
public final class DartToolchainManager {

    private static final String TAG = "DartToolchain";

    /** رابط أداة البيانات على GitHub Releases (مستودع عام). */
    public static final String RELEASE_BASE =
            "https://github.com/tofey-official/Sketchware-IA/releases/download/dart-toolchain";
    public static final String TOOLCHAIN_ZIP_URL = RELEASE_BASE + "/sumer-dart-toolchain-arm64.zip";

    private static final int CONNECT_TIMEOUT_MS = 20_000;
    private static final int READ_TIMEOUT_MS = 120_000;

    private DartToolchainManager() {
    }

    /** محرك Flutter الحقيقي المضمّن يدعم arm64-v8a فقط حالياً. */
    public static boolean isDeviceSupported() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) {
                return true;
            }
        }
        return false;
    }

    /** ثنائية Dart VM المضمّنة في APK الاستوديو. */
    public static File getDartBinary() {
        Context context = SketchApplication.getContext();
        return new File(context.getApplicationInfo().nativeLibraryDir, "libsumerdart.so");
    }

    public static boolean isDartBinaryAvailable() {
        File dart = getDartBinary();
        return dart.isFile() && dart.canExecute();
    }

    /** مجلد بيانات الأداة (مشترك بين كل المشاريع). */
    public static File getToolchainDir() {
        Context context = SketchApplication.getContext();
        return new File(context.getFilesDir(), "flutter_toolchain");
    }

    public static boolean isInstalled() {
        return validate(getToolchainDir());
    }

    private static boolean validate(File dir) {
        return dir.isDirectory()
                && new File(dir, "metadata.json").isFile()
                && new File(dir, "flutter_patched_sdk" + File.separator + "platform_strong.dill").isFile()
                && new File(dir, "flutter_packages" + File.separator + "package_config.json").isFile()
                && new File(dir, "driver" + File.separator + "bin" + File.separator + "kernel_driver.dart").isFile()
                && new File(dir, "driver" + File.separator + ".dart_tool" + File.separator + "package_config.json").isFile();
    }

    /**
     * يتأكد من جاهزية الأداة كاملة (يحمّل بيانات الأداة عند الحاجة) ويعيد مجلدها.
     */
    public static File ensureToolchain() throws IOException {
        if (!isDeviceSupported()) {
            throw new IOException(
                    "محرك Flutter الحقيقي يدعم معالجات arm64-v8a فقط حالياً. جهازك غير مدعوم.");
        }
        if (!isDartBinaryAvailable()) {
            throw new IOException(
                    "مترجم Dart الحقيقي غير مضمّن في هذا الإصدار من التطبيق (libsumerdart.so). "
                            + "حدّث Sumer Studio إلى أحدث إصدار مبني على GitHub.");
        }
        File dir = getToolchainDir();
        if (validate(dir)) {
            return dir;
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("تعذر إنشاء مجلد أداة Dart: " + dir.getAbsolutePath());
        }
        File zip = new File(dir, "sumer-toolchain.zip");
        LogUtil.d(TAG, "Downloading Dart toolchain data from " + TOOLCHAIN_ZIP_URL);
        download(TOOLCHAIN_ZIP_URL, zip);
        try {
            new KB().a(zip.getAbsolutePath(), dir.getAbsolutePath());
        } finally {
            FileUtil.deleteFile(zip.getAbsolutePath());
        }
        if (!validate(dir)) {
            throw new IOException(
                    "فشل التحقق من أداة Dart بعد التثبيت. تأكد من وجود مساحة كافية ثم أعد المحاولة.");
        }
        return dir;
    }

    private static void download(String url, File dest) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "SumerStudio-DartToolchain");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("فشل تحميل أداة Dart (HTTP " + code + "). تحقق من اتصال الإنترنت.");
            }
            long total = conn.getContentLengthLong();
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buffer = new byte[64 * 1024];
                long done = 0;
                long lastLogged = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    done += read;
                    if (total > 0 && done - lastLogged > 10L * 1024 * 1024) {
                        lastLogged = done;
                        LogUtil.d(TAG, "Toolchain download: "
                                + (done / (1024 * 1024)) + "/" + (total / (1024 * 1024)) + " MB");
                    }
                }
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
