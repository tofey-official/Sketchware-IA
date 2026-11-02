package mod.hey.studios.compiler.flutter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import a.a.a.ProjectBuilder;
import a.a.a.yq;
import mod.jbk.build.BuildProgressReceiver;
import pro.sketchware.activities.studio.model.FlutterAppBundler;
import pro.sketchware.utility.FileUtil;

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

        receiver.onProgress("Flutter SDK: Validating " + dartFiles.size() + " Dart sources...", 12);
        for (File dartFile : dartFiles) {
            String code = new String(Files.readAllBytes(dartFile.toPath()), StandardCharsets.UTF_8);
            String rel = projectRoot.toPath().relativize(dartFile.toPath()).toString().replace('\\', '/');
            FlutterAppBundler.validateDartSyntax(code, rel);
        }

        receiver.onProgress("Flutter SDK: Bundling Flutter assets & native runtime...", 12);
        yq workspace = builder.yq;
        File assetsDir = new File(workspace.assetsPath != null ? workspace.assetsPath : (workspace.projectMyscPath + File.separator + "app" + File.separator + "src" + File.separator + "main" + File.separator + "assets"));
        File flutterAssetsDir = new File(assetsDir, "flutter_assets");
        flutterAssetsDir.mkdirs();

        File fontManifest = new File(flutterAssetsDir, "FontManifest.json");
        if (!fontManifest.exists()) {
            FileUtil.writeFile(fontManifest.getAbsolutePath(), "[]");
        }

        File assetManifest = new File(flutterAssetsDir, "AssetManifest.bin");
        if (!assetManifest.exists()) {
            Files.write(assetManifest.toPath(), new byte[]{0x00, 0x00});
        }

        File userAssetsDir = new File(projectRoot, "assets");
        if (userAssetsDir.exists() && userAssetsDir.isDirectory()) {
            FlutterAppBundler.copyDirectory(userAssetsDir, new File(flutterAssetsDir, "assets"));
        }

        try {
            FlutterAppBundler.prepareFlutterAssets(projectRoot, workspace.applicationName != null ? workspace.applicationName : "Flutter App");
        } catch (Throwable ignored) {
        }
    }
}
