package mod.hey.studios.compiler.flutter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;

import a.a.a.ProjectBuilder;
import a.a.a.yq;
import mod.jbk.build.BuildProgressReceiver;
import mod.jbk.build.BuiltInLibraries;
import mod.hey.studios.compiler.dart.DartKernelCompiler;
import mod.hey.studios.compiler.dart.DartToolchainManager;
import pro.sketchware.utility.FileUtil;

/**
 * مجمّع Flutter الحقيقي — يشغّل مترجم Dart الرسمي (CFE) على الجهاز لإنتاج
 * kernel_blob.bin حقيقي، ويثبّت محرك Flutter الحقيقي (نسخة JIT) في الـ APK.
 * <p>
 * لم يعد هناك أي تحويل إلى HTML/JS: الكود يُجمَّع فعلياً ويعمل بمحرك Flutter
 * الرسمي المضمّن (libflutter.so) عبر FlutterActivity.
 */
public class FlutterCompiler {

    private final ProjectBuilder builder;

    public FlutterCompiler(ProjectBuilder builder) {
        this.builder = builder;
    }

    public void compile(BuildProgressReceiver receiver) throws Throwable {
        File projectRoot = FlutterCompilerUtil.getProjectRoot(builder);
        if (projectRoot == null || !projectRoot.exists()) {
            return;
        }

        List<File> dartFiles = FlutterCompilerUtil.getDartFiles(projectRoot);
        if (dartFiles.isEmpty()) {
            return;
        }

        yq workspace = builder.yq;

        receiver.onProgress("Dart: التأكد من أداة التجميع الحقيقية (أول مرة: تحميل الأداة)...", 11);
        File toolchain = DartToolchainManager.ensureToolchain(receiver);

        File assetsDir = new File(workspace.assetsPath != null
                ? workspace.assetsPath
                : (workspace.projectMyscPath + File.separator + "app" + File.separator
                + "src" + File.separator + "main" + File.separator + "assets"));
        File flutterAssetsDir = new File(assetsDir, "flutter_assets");
        if (!flutterAssetsDir.isDirectory() && !flutterAssetsDir.mkdirs()) {
            throw new IOException("تعذر إنشاء مجلد flutter_assets");
        }

        receiver.onProgress("Dart: تجميع " + dartFiles.size()
                + " ملف Dart بالمترجم الحقيقي (قد يستغرق عدة دقائق)...", 12);
        File kernelBlob = new File(flutterAssetsDir, "kernel_blob.bin");
        DartKernelCompiler.compile(workspace, toolchain, projectRoot, kernelBlob);

        receiver.onProgress("Flutter: تثبيت محرك Flutter الحقيقي...", 13);
        installRealEngine(toolchain);

        File fontManifest = new File(flutterAssetsDir, "FontManifest.json");
        if (!fontManifest.exists()) {
            FileUtil.writeFile(fontManifest.getAbsolutePath(), "[]");
        }
    }

    /**
     * يستبدل محرك Flutter المضمّن (نسخة release/AOT التي لا تقبل kernel)
     * بمحرك debug/JIT القادر على تحميل kernel_blob.bin.
     */
    private void installRealEngine(File toolchain) {
        File engineSo = new File(toolchain, "engine/libflutter.so");
        if (!engineSo.isFile()) {
            return;
        }

        // المسار الأساسي: مجلد jni الخاص بمكتبة flutter-embedding المضمنة
        try {
            File builtinJni = new File(
                    BuiltInLibraries.getLibraryPath(BuiltInLibraries.FLUTTER_EMBEDDING),
                    "jni/arm64-v8a");
            File target = new File(builtinJni, "libflutter.so");
            if (builtinJni.isDirectory()) {
                Files.copy(engineSo.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
                return;
            }
        } catch (Throwable t) {
            // نُكمل إلى المسار البديل
        }

        // المسار البديل: مجلد المكتبات الأصلية الخاص بالمشروع
        try {
            File nativeLibs = new File(builder.fpu.getPathNativelibs(builder.yq.sc_id),
                    "arm64-v8a");
            if (!nativeLibs.isDirectory() && !nativeLibs.mkdirs()) {
                return;
            }
            Files.copy(engineSo.toPath(), new File(nativeLibs, "libflutter.so").toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }
}
