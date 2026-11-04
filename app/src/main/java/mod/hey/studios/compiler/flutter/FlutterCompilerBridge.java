package mod.hey.studios.compiler.flutter;

import java.io.File;

import a.a.a.ProjectBuilder;
import a.a.a.yq;
import mod.jbk.build.BuildProgressReceiver;
import mod.jbk.build.BuiltInLibraries;
import pro.sketchware.util.library.BuiltInLibraryManager;

/**
 * Bridge between Sketchware's build pipeline and the official Flutter SDK integration.
 * Mirrors KotlinCompilerBridge to compile and integrate Flutter code and libraries.
 */
public class FlutterCompilerBridge {

    public static boolean areAnyDartFilesPresent(ProjectBuilder builder) {
        return FlutterCompilerUtil.areAnyDartFilesPresent(builder);
    }

    public static void compileFlutterCodeIfPossible(BuildProgressReceiver receiver, ProjectBuilder builder) throws Throwable {
        if (areAnyDartFilesPresent(builder)) {
            receiver.onProgress("Dart: تشغيل المترجم الحقيقي (Dart CFE)...", 11);
            new FlutterCompiler(builder).compile(receiver);
        }
    }

    public static void maybeAddFlutterBuiltInLibraryDependenciesIfPossible(ProjectBuilder builder, BuiltInLibraryManager builtInLibraryManager) {
        if (areAnyDartFilesPresent(builder)) {
            builtInLibraryManager.addLibrary(BuiltInLibraries.FLUTTER_EMBEDDING);
        }
    }

    public static String getFlutterHome(yq workspace) {
        return workspace.binDirectoryPath + File.separator + "flutter_home";
    }
}
