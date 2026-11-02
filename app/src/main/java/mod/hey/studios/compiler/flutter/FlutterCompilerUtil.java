package mod.hey.studios.compiler.flutter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import a.a.a.ProjectBuilder;
import a.a.a.wq;
import a.a.a.yq;

public class FlutterCompilerUtil {

    public static File getProjectRoot(ProjectBuilder builder) {
        if (builder == null || builder.yq == null) {
            return null;
        }
        yq workspace = builder.yq;
        if (workspace.sc_id != null && !workspace.sc_id.isEmpty()) {
            File studioRoot = new File(wq.getAndroidStudioProjectPath(workspace.sc_id));
            if (studioRoot.exists()) {
                return studioRoot;
            }
        }
        if (workspace.c != null && !workspace.c.isEmpty()) {
            return new File(workspace.c);
        }
        return null;
    }

    public static boolean areAnyDartFilesPresent(ProjectBuilder builder) {
        File root = getProjectRoot(builder);
        if (root == null || !root.exists()) {
            return false;
        }
        File libDir = new File(root, "lib");
        if (new File(libDir, "main.dart").exists() || new File(root, "pubspec.yaml").exists()) {
            return true;
        }
        return !getDartFiles(root).isEmpty();
    }

    public static List<File> getDartFiles(File root) {
        List<File> result = new ArrayList<>();
        if (root == null || !root.exists()) {
            return result;
        }
        File libDir = new File(root, "lib");
        if (libDir.exists() && libDir.isDirectory()) {
            collectDartFilesRecursively(libDir, result);
        } else {
            collectDartFilesRecursively(root, result);
        }
        return result;
    }

    private static void collectDartFilesRecursively(File dir, List<File> list) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                String name = file.getName();
                if (!name.equals("build") && !name.equals(".dart_tool") && !name.equals(".git")) {
                    collectDartFilesRecursively(file, list);
                }
            } else if (file.isFile() && file.getName().toLowerCase(Locale.US).endsWith(".dart")) {
                list.add(file);
            }
        }
    }
}
