package mod.hey.studios.compiler.dart;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import a.a.a.yq;
import mod.jbk.util.LogUtil;

/**
 * يجمع كود Dart الحقيقي إلى Flutter kernel (kernel_blob.bin) باستخدام
 * المترجم الرسمي (Dart CFE) الذي يعمل على الجهاز عبر Dart VM.
 * <p>
 * نتيجة الخطأ تكون نص كامل بتنسيق مترجم Dart الرسمي (file:line:col + الوصف)
 * حتى يعرف المستخدم بالضبط أين الخطأ في كوده — ويُرسل تلقائياً إلى Error Hub.
 */
public final class DartKernelCompiler {

    private static final String TAG = "DartKernelCompiler";
    /** حد أقصى لتجميع المشروع (20 دقيقة). */
    private static final long TIMEOUT_MS = 20 * 60 * 1000L;
    private static final int MAX_REPORTED_OUTPUT = 8000;

    private DartKernelCompiler() {
    }

    /**
     * @param workspace     مساحة عمل المشروع
     * @param toolchainDir  مجلد أداة Dart (من DartToolchainManager)
     * @param projectRoot   جذر مشروع Flutter (يحتوي lib/main.dart)
     * @param outputKernel  ملف الإخراج kernel_blob.bin
     */
    public static void compile(yq workspace, File toolchainDir, File projectRoot,
                               File outputKernel) throws IOException, InterruptedException {
        File dartBinary = DartToolchainManager.getDartBinary();
        File driverScript = new File(toolchainDir, "driver/bin/kernel_driver.dart");
        File driverPackages = new File(toolchainDir, "driver/.dart_tool/package_config.json");
        File patchedSdk = new File(toolchainDir, "flutter_patched_sdk");
        File platformDill = new File(patchedSdk, "platform_strong.dill");
        File librariesJson = new File(patchedSdk, "libraries.json");
        File frameworkPackages = new File(toolchainDir, "flutter_packages/package_config.json");
        File mainDart = new File(projectRoot, "lib/main.dart");

        if (!mainDart.isFile()) {
            throw new IOException("مشروع Flutter لا يحتوي lib/main.dart");
        }

        // دمج حزم الأداة (framework) مع حزم المستخدم (pubspec/.dart_tool) إن وجدت.
        File mergedPackages = mergePackageConfigs(toolchainDir, frameworkPackages,
                new File(projectRoot, ".dart_tool/package_config.json"), workspace);

        File tmpDir = new File(workspace.binDirectoryPath);
        if (!tmpDir.isDirectory()) {
            tmpDir.mkdirs();
        }
        File supportDir = new File(toolchainDir, "support");
        File dartLibDir = new File(toolchainDir, "dart-sdk/lib");

        List<String> cmd = new ArrayList<>();
        cmd.add(dartBinary.getAbsolutePath());
        // حزم سائق المترجم نفسه (VM-level)
        cmd.add("--packages=" + driverPackages.getAbsolutePath());
        cmd.add(driverScript.getAbsolutePath());
        // حزم كود المستخدم (CFE-level)
        cmd.add("--packages=" + mergedPackages.getAbsolutePath());
        cmd.add("--target=flutter");
        cmd.add("--platform=" + platformDill.getAbsolutePath());
        if (librariesJson.isFile()) {
            cmd.add("--libraries-json=" + librariesJson.getAbsolutePath());
        }
        cmd.add("--omit-platform");
        cmd.add("--output=" + outputKernel.getAbsolutePath());
        cmd.add(mainDart.getAbsolutePath());

        LogUtil.d(TAG, "Running real Dart compiler: " + String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("HOME", toolchainDir.getAbsolutePath());
        env.put("TMPDIR", tmpDir.getAbsolutePath());
        env.put("PATH", new File(toolchainDir, "dart-sdk/bin").getAbsolutePath()
                + ":/system/bin:/system/xbin");
        StringBuilder ld = new StringBuilder();
        // nativeLibraryDir: مكتبات الاعتماد المضمّنة بجانب libsumerdart.so
        ld.append(dartBinary.getParentFile().getAbsolutePath());
        if (supportDir.isDirectory()) {
            ld.append(":").append(supportDir.getAbsolutePath());
        }
        if (dartLibDir.isDirectory()) {
            ld.append(":").append(dartLibDir.getAbsolutePath());
        }
        env.put("LD_LIBRARY_PATH", ld.toString());

        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (output) {
                        output.append(line).append('\n');
                    }
                }
            } catch (IOException ignored) {
            }
        }, "DartCompiler-output");
        reader.setDaemon(true);
        reader.start();

        boolean finished = process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("انتهت مهلة تجميع Dart (20 دقيقة). المشروع كبير جداً أو الشيفرة تحتوي حلقاً ثقيلاً في وقت الترجمة.");
        }
        reader.join(5000);

        int exit = process.exitValue();
        String text;
        synchronized (output) {
            text = output.toString();
        }
        LogUtil.d(TAG, "Dart compiler exit=" + exit + " output:\n" + text);

        boolean kernelOk = outputKernel.isFile() && outputKernel.length() > 0;
        if (exit != 0 || !kernelOk) {
            String message = buildErrorMessage(exit, kernelOk, text);
            throw new IOException(message);
        }
        LogUtil.d(TAG, "kernel_blob.bin written: " + outputKernel.length() + " bytes");
    }

    /**
     * يدمج حزم Flutter الرسمية (من الأداة) مع حزم المشروع (package_config.json
     * الخاص بالمستخدم إن وجد — أي دعم pubspec المحلي). حزم المستخدم تُضاف
 * فوق حزم الأداة (المستخدم أولاً).
     */
    private static File mergePackageConfigs(File toolchainDir, File frameworkConfig,
                                            File userConfig, yq workspace) throws IOException {
        try {
            JSONObject merged = new JSONObject();
            merged.put("configVersion", 2);
            merged.put("generator", "sumer-studio");
            JSONArray packages = new JSONArray();

            JSONObject framework = new JSONObject(
                    readFile(frameworkConfig.getAbsolutePath()));
            JSONArray frameworkPackages = framework.optJSONArray("packages");
            if (frameworkPackages != null) {
                for (int i = 0; i < frameworkPackages.length(); i++) {
                    packages.put(frameworkPackages.getJSONObject(i));
                }
            }

            if (userConfig != null && userConfig.isFile()) {
                JSONObject user = new JSONObject(readFile(userConfig.getAbsolutePath()));
                JSONArray userPackages = user.optJSONArray("packages");
                if (userPackages != null) {
                    // استبدل أي حزمة متعارضة بحزمة المستخدم
                    for (int i = 0; i < userPackages.length(); i++) {
                        JSONObject userPkg = userPackages.getJSONObject(i);
                        String name = userPkg.optString("name");
                        boolean replaced = false;
                        for (int j = 0; j < packages.length(); j++) {
                            if (name.equals(packages.getJSONObject(j).optString("name"))) {
                                packages.put(j, userPkg);
                                replaced = true;
                                break;
                            }
                        }
                        if (!replaced) {
                            packages.put(userPkg);
                        }
                    }
                }
            }

            merged.put("packages", packages);
            File out = new File(workspace.binDirectoryPath, "sumer_package_config.json");
            writeFile(out, merged.toString());
            return out;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            // فشل الدمج لا يوقف البناء — نستخدم حزم الأداة فقط
            LogUtil.d(TAG, "package config merge failed, using toolchain config only: " + e);
            return frameworkConfig;
        }
    }

    private static String buildErrorMessage(int exit, boolean kernelOk, String compilerOutput) {
        String trimmed = compilerOutput == null ? "" : compilerOutput.trim();
        if (trimmed.length() > MAX_REPORTED_OUTPUT) {
            // نحتفظ ببداية الأخطاء (أهم) ونهاية السجل
            trimmed = trimmed.substring(0, MAX_REPORTED_OUTPUT / 2)
                    + "\n... [تم اختصار السجل] ...\n"
                    + trimmed.substring(trimmed.length() - MAX_REPORTED_OUTPUT / 2);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("فشل تجميع Dart");
        if (exit != 0) {
            sb.append(" (رمز الخروج ").append(exit).append(')');
        }
        sb.append(":\n\n");
        if (!trimmed.isEmpty()) {
            sb.append(trimmed);
        } else {
            sb.append(kernelOk
                    ? "المترجم لم يُرجع تفاصيل."
                    : "لم يتم إنتاج ملف kernel_blob.bin ولم يُرجع المترجم تفاصيل.");
        }
        return sb.toString();
    }

    private static String readFile(String path) throws IOException {
        java.nio.file.Path p = java.nio.file.Paths.get(path);
        return new String(java.nio.file.Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static void writeFile(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("تعذر إنشاء مجلد: " + parent);
        }
        java.nio.file.Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }
}
