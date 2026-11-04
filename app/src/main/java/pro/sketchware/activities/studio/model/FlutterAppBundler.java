package pro.sketchware.activities.studio.model;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import pro.sketchware.utility.FileUtil;

/**
 * Synchronizes Flutter Dart sources between the editor (lib/) and the build
 * assets (assets/flutter/).
 * <p>
 * The old Dart&rarr;HTML/WebView mock (index.html + flutter_runner.js) was
 * removed entirely. Dart code is now compiled by the real on-device Dart
 * compiler (CFE) at build time — see mod.hey.studios.compiler.dart and
 * mod.hey.studios.compiler.flutter.FlutterCompiler.
 */
public final class FlutterAppBundler {

    private FlutterAppBundler() {
    }

    /**
     * Bidirectional sync safeguard: keeps lib/main.dart and
     * assets/flutter/main.dart consistent, and mirrors lib/ into the assets
     * folder so project sources travel with the APK.
     *
     * @return always {@code null} — the HTML live runner was removed.
     */
    public static File prepareFlutterAssets(File projectRoot, String appName) throws IOException {
        if (projectRoot == null || !projectRoot.exists()) {
            throw new IOException("Project root does not exist: " + projectRoot);
        }

        File libDir = new File(projectRoot, "lib");
        if (!libDir.exists()) {
            libDir.mkdirs();
        }

        File assetsFlutterDir = new File(projectRoot, "app" + File.separator
                + "src" + File.separator + "main" + File.separator
                + "assets" + File.separator + "flutter");
        if (!assetsFlutterDir.exists()) {
            assetsFlutterDir.mkdirs();
        }

        File mainDart = new File(libDir, "main.dart");
        File assetMainDart = new File(assetsFlutterDir, "main.dart");

        // If the user previously edited assetMainDart, preserve their changes.
        if (assetMainDart.exists() && assetMainDart.length() > 0) {
            if (!mainDart.exists() || assetMainDart.lastModified() > mainDart.lastModified() + 1000L) {
                String assetContent = new String(Files.readAllBytes(assetMainDart.toPath()),
                        StandardCharsets.UTF_8);
                if (!assetContent.trim().isEmpty()) {
                    FileUtil.writeFile(mainDart.getAbsolutePath(), assetContent);
                }
            }
        }

        String mainDartCode;
        if (mainDart.exists()) {
            mainDartCode = new String(Files.readAllBytes(mainDart.toPath()), StandardCharsets.UTF_8);
        } else {
            mainDartCode = StudioLanguageConfigurer.buildFlutterMainDart(
                    appName == null ? "Flutter App" : appName);
            FileUtil.writeFile(mainDart.getAbsolutePath(), mainDartCode);
        }

        // Mirror lib/ into assets/flutter/lib and keep main.dart copy in sync.
        copyDirectory(libDir, new File(assetsFlutterDir, "lib"));
        FileUtil.writeFile(assetMainDart.getAbsolutePath(), mainDartCode);

        return null;
    }

    public static void copyDirectory(File src, File dest) throws IOException {
        if (!src.exists()) {
            return;
        }
        if (src.isDirectory()) {
            if (!dest.exists()) {
                dest.mkdirs();
            }
            File[] files = src.listFiles();
            if (files != null) {
                for (File file : files) {
                    copyDirectory(file, new File(dest, file.getName()));
                }
            }
        } else {
            Files.copy(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
