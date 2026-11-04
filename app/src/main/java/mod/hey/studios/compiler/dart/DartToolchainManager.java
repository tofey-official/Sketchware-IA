package mod.hey.studios.compiler.dart;

import android.content.Context;
import android.os.Build;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import mod.jbk.build.BuildProgressReceiver;
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
 * <p>
 * مبادئ المتانة في هذه النسخة:
 * <ul>
 *   <li>قفل تزامن: لن يعمل تحميلان/استخراجان لنفس الأداة في نفس الوقت
 *       (يمنع سباق "حذف الملف أثناء إعادة البناء").</li>
 *   <li>تحميل ذري: يُكتب الملف باسم ‎.part ثم يُنقل باسمه النهائي بعد
 *       اكتماله والتحقق من حجمه — لا يُستخرج أبداً ملف ناقص.</li>
 *   <li>استكمال التحميل: إذا انقطع الاتصال يُستأنف من حيث توقف (HTTP Range)
 *       بدل البدء من الصفر (حجم الأداة مئات الميغابايت).</li>
 *   <li>استخراج مكتوب داخلياً بخطأ صريح (بدل الكلاس المُبهَم KB الذي يبتلع
 *       الاستثناءات) وذاكرة وسيطة كبيرة للتسرّع.</li>
 *   <li>تقدم مرئي في شاشة البناء حتى لا يبدو التطبيق "متجمداً" أثناء التحميل.</li>
 * </ul>
 */
public final class DartToolchainManager {

    private static final String TAG = "DartToolchain";

    /** رابط أداة البيانات على GitHub Releases (مستودع عام). */
    public static final String RELEASE_BASE =
            "https://github.com/tofey-official/Sketchware-IA/releases/download/dart-toolchain";
    public static final String TOOLCHAIN_ZIP_URL = RELEASE_BASE + "/sumer-dart-toolchain-arm64.zip";

    private static final int CONNECT_TIMEOUT_MS = 20_000;
    private static final int READ_TIMEOUT_MS = 120_000;
    /** عدد محاولات التحميل قبل الاستسلام. */
    private static final int DOWNLOAD_ATTEMPTS = 3;
    /** أقل حجم معقول للأداة المضغوطة (الحجم الفعلي ~297 ميغابايت). */
    private static final long MIN_ZIP_BYTES = 100L * 1024 * 1024;
    /** التباعد بين رسائل التقدم المرئية أثناء التحميل. */
    private static final long PROGRESS_STEP_BYTES = 5L * 1024 * 1024;

    /** يمنع تحميل/استخراج الأداة من عمليتي بناء متزامنتين. */
    private static final Object INSTALL_LOCK = new Object();

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
     *
     * @param progress مستقبل تقدم البناء لإظهار رسائل التحميل في شاشة البناء
     *                 (يمكن أن يكون null في الاستدعاءات الخلفية).
     */
    public static File ensureToolchain(BuildProgressReceiver progress) throws IOException {
        if (!isDeviceSupported()) {
            throw new IOException(
                    "محرك Flutter الحقيقي يدعم معالجات arm64-v8a فقط حالياً. جهازك غير مدعوم.");
        }
        if (!isDartBinaryAvailable()) {
            throw new IOException(
                    "مترجم Dart الحقيقي غير مضمّن في هذا الإصدار من التطبيق (libsumerdart.so). "
                            + "حدّث Sumer Studio إلى أحدث إصدار مبني على GitHub.");
        }
        synchronized (INSTALL_LOCK) {
            File dir = getToolchainDir();
            if (validate(dir)) {
                return dir;
            }
            // حالة قديمة/ناقصة (تحميل سابق انقطع أو استخراج ناقص): نبدأ نظيفاً.
            if (dir.isDirectory()) {
                LogUtil.d(TAG, "Removing stale toolchain dir: " + dir.getAbsolutePath());
                FileUtil.deleteFile(dir.getAbsolutePath());
            }
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("تعذر إنشاء مجلد أداة Dart: " + dir.getAbsolutePath());
            }

            File zip = new File(dir, "sumer-toolchain.zip");
            downloadWithResume(TOOLCHAIN_ZIP_URL, zip, progress);

            try {
                extractZip(zip, dir);
            } finally {
                FileUtil.deleteFile(zip.getAbsolutePath());
            }

            if (!validate(dir)) {
                throw new IOException(
                        "فشل التحقق من أداة Dart بعد التثبيت: " + describeMissing(dir)
                                + ". تأكد من وجود مساحة تخزين كافية ثم أعد المحاولة.");
            }
            LogUtil.d(TAG, "Toolchain ready at " + dir.getAbsolutePath());
            return dir;
        }
    }

    /** نسخة قديمة بدون تقدم مرئي (للاستدعاءات الخلفية). */
    public static File ensureToolchain() throws IOException {
        return ensureToolchain(null);
    }

    // ---------------------------------------------------------------------
    // التحميل (ذري + استكمال + محاولات متعددة)
    // ---------------------------------------------------------------------

    private static void downloadWithResume(String url, File zip, BuildProgressReceiver progress)
            throws IOException {
        File part = new File(zip.getParentFile(), zip.getName() + ".part");
        IOException last = null;
        for (int attempt = 1; attempt <= DOWNLOAD_ATTEMPTS; attempt++) {
            try {
                notify(progress, "Dart: تحميل أداة التجميع الحقيقية (المحاولة " + attempt
                        + "/" + DOWNLOAD_ATTEMPTS + ") — قد يستغرق عدة دقائق حسب سرعة الإنترنت...");
                downloadAttempt(url, part, progress, attempt);
                if (!part.isFile()) {
                    throw new IOException("لم يُنشأ ملف التحميل (" + part.getAbsolutePath() + ")");
                }
                if (part.length() < MIN_ZIP_BYTES) {
                    throw new IOException("ملف الأداة المُحمَّل غير مكتمل ("
                            + megabytes(part.length()) + " ميغابايت فقط).");
                }
                // تحميل مكتمل: نقله بالاسم النهائي ذرياً.
                try {
                    Files.move(part.toPath(), zip.toPath(), StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(part.toPath(), zip.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                if (!zip.isFile() || zip.length() < MIN_ZIP_BYTES) {
                    throw new IOException("فشل تجهيز ملف الأداة بعد التحميل ("
                            + zip.getAbsolutePath() + ")");
                }
                return;
            } catch (IOException e) {
                last = e;
                LogUtil.d(TAG, "Download attempt " + attempt + " failed: " + e);
                // لا نحذف part بين المحاولات حتى يُستأنف التحميل من حيث توقف.
                try {
                    Thread.sleep(2000L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("أُلغي تحميل أداة Dart.", ie);
                }
            }
        }
        throw new IOException("تعذر تحميل أداة Dart بعد " + DOWNLOAD_ATTEMPTS + " محاولات: "
                + (last != null ? last.getMessage() : "سبب غير معروف")
                + ". تحقق من اتصال الإنترنت ومساحة التخزين ثم أعد المحاولة.", last);
    }

    private static void downloadAttempt(String url, File part, BuildProgressReceiver progress,
                                        int attempt) throws IOException {
        long already = part.isFile() ? part.length() : 0;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "SumerStudio-DartToolchain");
            boolean resume = already > 0;
            if (resume) {
                conn.setRequestProperty("Range", "bytes=" + already + "-");
            }
            int code = conn.getResponseCode();
            if (resume && code == 416) {
                // الملف المؤقت أكبر من الملف الفعلي على السيرفر (سيرفر/إصدار مختلف) —
                // نحذفه لتبدأ المحاولة التالية من الصفر.
                conn.disconnect();
                conn = null;
                FileUtil.deleteFile(part.getAbsolutePath());
                throw new IOException("استئناف التحميل غير ممكن (HTTP 416) — ستبدأ المحاولة التالية من الصفر.");
            }
            if (code < 200 || code >= 300) {
                throw new IOException("فشل تحميل أداة Dart (HTTP " + code + "). تحقق من اتصال الإنترنت.");
            }
            boolean appending = resume && code == HttpURLConnection.HTTP_PARTIAL; // 206
            long total;
            if (appending) {
                total = already + conn.getContentLengthLong();
            } else {
                total = conn.getContentLengthLong();
                already = 0;
            }
            if (total > 0 && total < MIN_ZIP_BYTES) {
                throw new IOException("الأداة على السيرفر غير مكتملة ("
                        + megabytes(total) + " ميغابايت فقط).");
            }
            try (InputStream in = conn.getInputStream();
                 OutputStream out = new FileOutputStream(part, appending)) {
                byte[] buffer = new byte[256 * 1024];
                long done = already;
                long lastReported = done;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    done += read;
                    if (progress != null && done - lastReported >= PROGRESS_STEP_BYTES) {
                        lastReported = done;
                        if (total > 0) {
                            notify(progress, "Dart: تحميل أداة التجميع "
                                    + megabytes(done) + "/" + megabytes(total) + " ميغابايت...");
                        } else {
                            notify(progress, "Dart: تحميل أداة التجميع "
                                    + megabytes(done) + " ميغابايت...");
                        }
                    }
                }
            }
            if (total > 0 && part.length() != total) {
                throw new IOException("حجم التحميل غير مطابق ("
                        + megabytes(part.length()) + " من " + megabytes(total)
                        + " ميغابايت) — سيعاد استكماله.");
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // ---------------------------------------------------------------------
    // الاستخراج (خطأ صريح، ذاكرة كبيرة، حماية من zip-slip)
    // ---------------------------------------------------------------------

    private static void extractZip(File zip, File destDir) throws IOException {
        if (!zip.isFile()) {
            throw new IOException("ملف أداة Dart اختفى قبل فك الضغط ("
                    + zip.getAbsolutePath() + ") — أعد المحاولة.");
        }
        if (zip.length() < MIN_ZIP_BYTES) {
            throw new IOException("ملف أداة Dart غير مكتمل ("
                    + megabytes(zip.length()) + " ميغابايت فقط) — لن يُستخرج. أعد المحاولة.");
        }
        byte[] buffer = new byte[1024 * 1024];
        String destCanonical = destDir.getCanonicalPath() + File.separator;
        long entries = 0;
        try (ZipInputStream zin = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(zip), 1024 * 1024))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                File out = new File(destDir, entry.getName());
                String canonical = out.getCanonicalPath();
                if (!canonical.startsWith(destCanonical)
                        && !canonical.equals(destDir.getCanonicalPath())) {
                    throw new IOException("مسار غير آمن داخل أرشيف الأداة: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    if (!out.isDirectory() && !out.mkdirs()) {
                        throw new IOException("تعذر إنشاء مجلد داخل الأداة: " + canonical);
                    }
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("تعذر إنشاء مجلد داخل الأداة: " + parent.getAbsolutePath());
                }
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    int n;
                    while ((n = zin.read(buffer)) != -1) {
                        fos.write(buffer, 0, n);
                    }
                }
                entries++;
            }
        } catch (IOException e) {
            throw new IOException("فشل فك ضغط أداة Dart بعد " + entries + " ملف: "
                    + e.getMessage(), e);
        }
        if (entries < 100) {
            throw new IOException("أرشيف أداة Dart يبدو غير صالح (عدد الملفات المستخرجة: " + entries + ").");
        }
        LogUtil.d(TAG, "Extracted " + entries + " toolchain files");
    }

    // ---------------------------------------------------------------------
    // أدوات مساعدة
    // ---------------------------------------------------------------------

    private static String describeMissing(File dir) {
        String[] required = {
                "metadata.json",
                "flutter_patched_sdk/platform_strong.dill",
                "flutter_packages/package_config.json",
                "driver/bin/kernel_driver.dart",
                "driver/.dart_tool/package_config.json"
        };
        StringBuilder sb = new StringBuilder();
        for (String rel : required) {
            if (!new File(dir, rel.replace('/', File.separatorChar)).isFile()) {
                if (sb.length() > 0) {
                    sb.append("، ");
                }
                sb.append(rel);
            }
        }
        return sb.length() > 0 ? sb.toString() : "ملفات غير معروفة";
    }

    private static String megabytes(long bytes) {
        long mb = bytes / (1024 * 1024);
        return String.valueOf(mb);
    }

    private static void notify(BuildProgressReceiver progress, String message) {
        LogUtil.d(TAG, message);
        if (progress != null) {
            progress.onProgress(message, 11);
        }
    }
}
