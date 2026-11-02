package pro.sketchware.activities.studio.model;

import java.io.File;
import java.io.IOException;

import pro.sketchware.utility.FileUtil;

/**
 * Handles template configuration, source file generation, and Gradle adjustments
 * based on the selected StudioLanguage.
 * Easily extensible when new programming languages are added in the future.
 */
public final class StudioLanguageConfigurer {

    private StudioLanguageConfigurer() {
    }

    public static void configure(File projectRoot, StudioLanguage language, String packageName, String appName) throws IOException {
        configure(projectRoot, language, packageName, appName, true);
    }

    public static void configure(File projectRoot, StudioLanguage language, String packageName, String appName, boolean isNewProject) throws IOException {
        if (projectRoot == null || !projectRoot.exists() || language == null) {
            return;
        }

        File appDir = new File(projectRoot, "app");
        File mainDir = new File(appDir, "src" + File.separator + "main");
        File javaDir = new File(mainDir, "java");
        File packageDir = new File(javaDir, packageName.replace('.', File.separatorChar));
        if (!packageDir.exists()) {
            packageDir.mkdirs();
        }

        File mainActivityJava = new File(packageDir, "MainActivity.java");
        File mainActivityKt = new File(packageDir, "MainActivity.kt");

        File appBuildFile = new File(appDir, "build.gradle");
        File rootBuildFile = new File(projectRoot, "build.gradle");

        if (isNewProject) {
            switch (language) {
                case KOTLIN:
                    // Write Kotlin MainActivity
                    String kotlinCode = buildKotlinMainActivity(packageName);
                    FileUtil.writeFile(mainActivityKt.getAbsolutePath(), kotlinCode);
                    // Remove Java MainActivity
                    if (mainActivityJava.exists()) {
                        FileUtil.deleteFile(mainActivityJava.getAbsolutePath());
                    }
                    configureGradleForKotlin(appBuildFile, rootBuildFile);
                    break;

                case BOTH:
                    // Both Java & Kotlin: Keep Java MainActivity and provide a Kotlin helper class
                    if (!mainActivityJava.exists()) {
                        String javaCode = buildJavaMainActivity(packageName);
                        FileUtil.writeFile(mainActivityJava.getAbsolutePath(), javaCode);
                    }
                    File appUtilsKt = new File(packageDir, "AppUtils.kt");
                    String appUtilsCode = buildKotlinAppUtils(packageName);
                    FileUtil.writeFile(appUtilsKt.getAbsolutePath(), appUtilsCode);
                    configureGradleForKotlin(appBuildFile, rootBuildFile);
                    break;

                case JAVA:
                default:
                    // Java default: ensure Java MainActivity exists
                    if (!mainActivityJava.exists()) {
                        String javaCode = buildJavaMainActivity(packageName);
                        FileUtil.writeFile(mainActivityJava.getAbsolutePath(), javaCode);
                    }
                    if (mainActivityKt.exists()) {
                        FileUtil.deleteFile(mainActivityKt.getAbsolutePath());
                    }
                    break;
            }
        } else {
            // Updating existing project: keep user source files, but ensure Kotlin plugins/dependencies in Gradle if Kotlin is enabled
            if (language.supportsKotlin()) {
                configureGradleForKotlin(appBuildFile, rootBuildFile);
            }
        }
    }

    private static void configureGradleForKotlin(File appBuildFile, File rootBuildFile) {
        if (rootBuildFile != null && rootBuildFile.exists()) {
            String rootContent = FileUtil.readFile(rootBuildFile.getAbsolutePath());
            if (!rootContent.contains("org.jetbrains.kotlin.android")) {
                if (rootContent.contains("id 'com.android.application' version")) {
                    rootContent = rootContent.replace(
                            "id 'com.android.application' version '9.4.1' apply false",
                            "id 'com.android.application' version '9.4.1' apply false\n    id 'org.jetbrains.kotlin.android' version '2.0.21' apply false"
                    );
                }
                FileUtil.writeFile(rootBuildFile.getAbsolutePath(), rootContent);
            }
        }

        if (appBuildFile != null && appBuildFile.exists()) {
            String appContent = FileUtil.readFile(appBuildFile.getAbsolutePath());
            if (!appContent.contains("org.jetbrains.kotlin.android") && !appContent.contains("kotlin-android")) {
                appContent = appContent.replace(
                        "id 'com.android.application'",
                        "id 'com.android.application'\n    id 'org.jetbrains.kotlin.android'"
                );
            }
            if (!appContent.contains("core-ktx")) {
                appContent = appContent.replace(
                        "dependencies {",
                        "dependencies {\n    implementation 'androidx.core:core-ktx:1.15.0'"
                );
            }
            if (!appContent.contains("kotlinOptions")) {
                appContent = appContent.replace(
                        "compileOptions {",
                        "kotlinOptions {\n        jvmTarget = '17'\n    }\n\n    compileOptions {"
                );
            }
            FileUtil.writeFile(appBuildFile.getAbsolutePath(), appContent);
        }
    }

    private static String buildKotlinMainActivity(String packageName) {
        return "package " + packageName + "\n\n"
                + "import android.os.Bundle\n"
                + "import androidx.activity.enableEdgeToEdge\n"
                + "import androidx.appcompat.app.AppCompatActivity\n"
                + "import androidx.core.view.ViewCompat\n"
                + "import androidx.core.view.WindowInsetsCompat\n"
                + "import com.google.android.material.appbar.MaterialToolbar\n\n"
                + "class MainActivity : AppCompatActivity() {\n\n"
                + "    override fun onCreate(savedInstanceState: Bundle?) {\n"
                + "        super.onCreate(savedInstanceState)\n"
                + "        enableEdgeToEdge()\n"
                + "        setContentView(R.layout.activity_main)\n"
                + "        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->\n"
                + "            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())\n"
                + "            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)\n"
                + "            insets\n"
                + "        }\n\n"
                + "        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)\n"
                + "        setSupportActionBar(toolbar)\n"
                + "    }\n"
                + "}\n";
    }

    private static String buildJavaMainActivity(String packageName) {
        return "package " + packageName + ";\n\n"
                + "import android.os.Bundle;\n"
                + "import androidx.activity.EdgeToEdge;\n"
                + "import androidx.appcompat.app.AppCompatActivity;\n"
                + "import androidx.core.graphics.Insets;\n"
                + "import androidx.core.view.ViewCompat;\n"
                + "import androidx.core.view.WindowInsetsCompat;\n"
                + "import com.google.android.material.appbar.MaterialToolbar;\n\n"
                + "public class MainActivity extends AppCompatActivity {\n\n"
                + "    @Override\n"
                + "    protected void onCreate(Bundle savedInstanceState) {\n"
                + "        super.onCreate(savedInstanceState);\n"
                + "        EdgeToEdge.enable(this);\n"
                + "        setContentView(R.layout.activity_main);\n"
                + "        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {\n"
                + "            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());\n"
                + "            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);\n"
                + "            return insets;\n"
                + "        });\n\n"
                + "        MaterialToolbar toolbar = findViewById(R.id.toolbar);\n"
                + "        setSupportActionBar(toolbar);\n"
                + "    }\n"
                + "}\n";
    }

    private static String buildKotlinAppUtils(String packageName) {
        return "package " + packageName + "\n\n"
                + "import android.content.Context\n"
                + "import android.widget.Toast\n\n"
                + "object AppUtils {\n"
                + "    @JvmStatic\n"
                + "    fun showToast(context: Context, message: String) {\n"
                + "        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()\n"
                + "    }\n"
                + "}\n";
    }
}
