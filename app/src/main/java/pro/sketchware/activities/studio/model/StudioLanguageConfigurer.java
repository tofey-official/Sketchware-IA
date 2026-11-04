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

                case FLUTTER:
                    // Write Flutter Host MainActivity to launch the Flutter Material 3 runtime
                    String flutterHostCode = buildFlutterHostMainActivity(packageName);
                    FileUtil.writeFile(mainActivityJava.getAbsolutePath(), flutterHostCode);
                    if (mainActivityKt.exists()) {
                        FileUtil.deleteFile(mainActivityKt.getAbsolutePath());
                    }
                    // Delete confusing Android activity_main.xml layout
                    File layoutDir = new File(projectRoot, "app" + File.separator + "src" + File.separator + "main" + File.separator + "res" + File.separator + "layout");
                    File mainLayout = new File(layoutDir, "activity_main.xml");
                    if (mainLayout.exists()) {
                        FileUtil.deleteFile(mainLayout.getAbsolutePath());
                    }
                    configureFlutterProject(projectRoot, appName, packageName);
                    try {
                        FlutterAppBundler.prepareFlutterAssets(projectRoot, appName);
                    } catch (Exception ignored) {
                    }
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

    private static void configureFlutterProject(File projectRoot, String appName, String packageName) {
        String cleanAppName = (appName == null || appName.trim().isEmpty()) ? "my_flutter_app" : appName.trim().toLowerCase().replaceAll("[^a-z0-9_]", "_");
        if (cleanAppName.isEmpty() || Character.isDigit(cleanAppName.charAt(0))) {
            cleanAppName = "app_" + cleanAppName;
        }

        File libDir = new File(projectRoot, "lib");
        File screensDir = new File(libDir, "screens");
        File widgetsDir = new File(libDir, "widgets");
        File modelsDir = new File(libDir, "models");
        File constantsDir = new File(libDir, "constants");
        screensDir.mkdirs();
        widgetsDir.mkdirs();
        modelsDir.mkdirs();
        constantsDir.mkdirs();

        File assetsDir = new File(projectRoot, "assets");
        File imagesDir = new File(assetsDir, "images");
        File fontsDir = new File(assetsDir, "fonts");
        imagesDir.mkdirs();
        fontsDir.mkdirs();
        try {
            new File(imagesDir, ".gitkeep").createNewFile();
            new File(fontsDir, ".gitkeep").createNewFile();
        } catch (Exception ignored) {
        }

        String safeAppTitle = (appName == null ? "Flutter App" : appName);

        File mainDart = new File(libDir, "main.dart");
        FileUtil.writeFile(mainDart.getAbsolutePath(), buildFlutterMainDart(safeAppTitle));

        File homeScreenDart = new File(screensDir, "home_screen.dart");
        FileUtil.writeFile(homeScreenDart.getAbsolutePath(), buildFlutterHomeScreenDart(safeAppTitle));

        File customButtonDart = new File(widgetsDir, "custom_button.dart");
        FileUtil.writeFile(customButtonDart.getAbsolutePath(), buildFlutterCustomButtonDart());

        File appColorsDart = new File(constantsDir, "app_colors.dart");
        FileUtil.writeFile(appColorsDart.getAbsolutePath(), buildFlutterAppColorsDart());

        File itemModelDart = new File(modelsDir, "item_model.dart");
        FileUtil.writeFile(itemModelDart.getAbsolutePath(), buildFlutterItemModelDart());

        File pubspecFile = new File(projectRoot, "pubspec.yaml");
        FileUtil.writeFile(pubspecFile.getAbsolutePath(), buildPubspecYaml(cleanAppName));

        File analysisFile = new File(projectRoot, "analysis_options.yaml");
        FileUtil.writeFile(analysisFile.getAbsolutePath(), buildAnalysisOptionsYaml());

        File readmeFile = new File(projectRoot, "README.md");
        if (!readmeFile.exists()) {
            FileUtil.writeFile(readmeFile.getAbsolutePath(), "# " + cleanAppName + "\n\nA new Flutter project created with Sumer Studio.\n");
        }
    }

    public static String buildFlutterHostMainActivity(String packageName) {
        // Sumer Error Hub: نشاط Flutter أصلي مع رصد الكراشات وأخطاء محرك Flutter (logcat) وإرسالها للوحة المطور
        return "package " + packageName + ";\n\n"
                + "import android.os.Build;\n"
                + "import android.os.Bundle;\n\n"
                + "import java.io.BufferedReader;\n"
                + "import java.io.InputStreamReader;\n"
                + "import java.io.OutputStream;\n"
                + "import java.io.PrintWriter;\n"
                + "import java.io.StringWriter;\n"
                + "import java.net.HttpURLConnection;\n"
                + "import java.net.URL;\n"
                + "import java.nio.charset.StandardCharsets;\n\n"
                + "import io.flutter.embedding.android.FlutterActivity;\n\n"
                + "/**\n"
                + " * Native Flutter Activity generated by Sumer Studio.\n"
                + " * Inherits from official FlutterActivity to launch native Flutter Engine.\n"
                + " * Includes Sumer Error Hub crash + Flutter error reporting.\n"
                + " */\n"
                + "public class MainActivity extends FlutterActivity {\n\n"
                + "    private static final String EH_URL = \"https://camillecyrm.serv00.net/errorhub/api.php?action=create\";\n"
                + "    private static final String EH_KEY = \"sumer_d77c474a5263587275765f401cead446\";\n\n"
                + "    @Override\n"
                + "    protected void onCreate(Bundle savedInstanceState) {\n"
                + "        super.onCreate(savedInstanceState);\n"
                + "        initErrorReporting();\n"
                + "    }\n\n"
                + "    private void initErrorReporting() {\n"
                + "        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();\n"
                + "        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {\n"
                + "            @Override\n"
                + "            public void uncaughtException(final Thread thread, final Throwable throwable) {\n"
                + "                try {\n"
                + "                    StringWriter sw = new StringWriter();\n"
                + "                    throwable.printStackTrace(new PrintWriter(sw));\n"
                + "                    Thread t = ehPost(\"crash\", \"Crash in \" + thread.getName() + \": \"\n"
                + "                            + throwable.getClass().getName() + \": \" + throwable.getMessage(), sw.toString());\n"
                + "                    if (t != null) t.join(1500L);\n"
                + "                } catch (Throwable ignored) {\n"
                + "                }\n"
                + "                if (previous != null) previous.uncaughtException(thread, throwable);\n"
                + "            }\n"
                + "        });\n"
                + "        Thread watcher = new Thread(new Runnable() {\n"
                + "            @Override\n"
                + "            public void run() {\n"
                + "                BufferedReader reader = null;\n"
                + "                try {\n"
                + "                    Process p = Runtime.getRuntime().exec(new String[]{\"logcat\", \"-v\", \"brief\", \"*:E\"});\n"
                + "                    reader = new BufferedReader(new InputStreamReader(p.getInputStream()), 8192);\n"
                + "                    java.util.List<String> batch = new java.util.ArrayList<String>();\n"
                + "                    String line;\n"
                + "                    while ((line = reader.readLine()) != null) {\n"
                + "                        if (line.length() < 20) continue;\n"
                + "                        if (line.contains(\"EGL_emulation\") || line.contains(\"OpenGLRenderer\")\n"
                + "                                || line.contains(\"ViewRootImpl\") || line.contains(\"chatty\")) continue;\n"
                + "                        batch.add(line);\n"
                + "                        if (batch.size() >= 100) {\n"
                + "                            ehPost(\"flutter\", stripPrefix(batch.get(0)), join(batch));\n"
                + "                            batch.clear();\n"
                + "                        }\n"
                + "                    }\n"
                + "                } catch (Throwable ignored) {\n"
                + "                } finally {\n"
                + "                    if (reader != null) {\n"
                + "                        try { reader.close(); } catch (Throwable ignored2) {}\n"
                + "                    }\n"
                + "                }\n"
                + "            }\n"
                + "        }, \"ErrorHub-logcat\");\n"
                + "        watcher.setDaemon(true);\n"
                + "        watcher.start();\n"
                + "    }\n\n"
                + "    private static String stripPrefix(String line) {\n"
                + "        int idx = line.indexOf(\"]: \");\n"
                + "        return idx >= 0 ? line.substring(idx + 3) : line;\n"
                + "    }\n\n"
                + "    private static String join(java.util.List<String> lines) {\n"
                + "        StringBuilder sb = new StringBuilder();\n"
                + "        for (String l : lines) sb.append(l).append('\\n');\n"
                + "        return sb.toString();\n"
                + "    }\n\n"
                + "    private static Thread ehPost(final String type, final String message, final String trace) {\n"
                + "        Thread t = new Thread(new Runnable() {\n"
                + "            @Override\n"
                + "            public void run() {\n"
                + "                HttpURLConnection conn = null;\n"
                + "                try {\n"
                + "                    String device = Build.MODEL + \" - Android \" + Build.VERSION.RELEASE;\n"
                + "                    String body = \"{\\\"project_name\\\":\\\"Flutter App\\\"\"\n"
                + "                            + \",\\\"project_id\\\":\\\"\\\"\"\n"
                + "                            + \",\\\"error_type\\\":\\\"\" + type + \"\\\"\"\n"
                + "                            + \",\\\"message\\\":\\\"\" + ehEsc(message) + \"\\\"\"\n"
                + "                            + \",\\\"stack_trace\\\":\\\"\" + ehEsc(trace) + \"\\\"\"\n"
                + "                            + \",\\\"device_info\\\":\\\"\" + ehEsc(device) + \"\\\"\"\n"
                + "                            + \",\\\"app_version\\\":\\\"flutter-native\\\"\"\n"
                + "                            + \",\\\"source\\\":\\\"sumer-flutter-native\\\"}\";\n"
                + "                    URL url = new URL(EH_URL);\n"
                + "                    conn = (HttpURLConnection) url.openConnection();\n"
                + "                    conn.setRequestMethod(\"POST\");\n"
                + "                    conn.setConnectTimeout(10000);\n"
                + "                    conn.setReadTimeout(10000);\n"
                + "                    conn.setDoOutput(true);\n"
                + "                    conn.setRequestProperty(\"Content-Type\", \"application/json; charset=utf-8\");\n"
                + "                    conn.setRequestProperty(\"X-API-Key\", EH_KEY);\n"
                + "                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);\n"
                + "                    conn.setFixedLengthStreamingMode(bytes.length);\n"
                + "                    OutputStream os = conn.getOutputStream();\n"
                + "                    os.write(bytes);\n"
                + "                    os.flush();\n"
                + "                    os.close();\n"
                + "                    conn.getResponseCode();\n"
                + "                } catch (Throwable ignored) {\n"
                + "                } finally {\n"
                + "                    if (conn != null) conn.disconnect();\n"
                + "                }\n"
                + "            }\n"
                + "        }, \"ErrorHub-post\");\n"
                + "        t.setDaemon(true);\n"
                + "        t.start();\n"
                + "        return t;\n"
                + "    }\n\n"
                + "    private static String ehEsc(String s) {\n"
                + "        if (s == null) return \"\";\n"
                + "        return s.replace(\"\\\\\", \"\\\\\\\\\").replace(\"\\\"\", \"\\\\\\\"\")\n"
                + "                .replace(\"\\n\", \"\\\\n\").replace(\"\\r\", \"\\\\r\").replace(\"\\t\", \"\\\\t\");\n"
                + "    }\n"
                + "}\n";
    }

    public static String buildFlutterMainDart(String appTitle) {
        String safeTitle = appTitle.replace("'", "\\'");
        return "import 'package:flutter/material.dart';\n"
                + "import 'screens/home_screen.dart';\n"
                + "import 'constants/app_colors.dart';\n\n"
                + "void main() {\n"
                + "  runApp(const MyApp());\n"
                + "}\n\n"
                + "class MyApp extends StatelessWidget {\n"
                + "  const MyApp({super.key});\n\n"
                + "  @override\n"
                + "  Widget build(BuildContext context) {\n"
                + "    return MaterialApp(\n"
                + "      title: '" + safeTitle + "',\n"
                + "      debugShowCheckedModeBanner: false,\n"
                + "      theme: ThemeData(\n"
                + "        colorScheme: ColorScheme.fromSeed(seedColor: AppColors.primary),\n"
                + "        useMaterial3: true,\n"
                + "      ),\n"
                + "      home: const HomeScreen(title: '" + safeTitle + "'),\n"
                + "    );\n"
                + "  }\n"
                + "}\n";
    }

    public static String buildFlutterHomeScreenDart(String appTitle) {
        String safeTitle = appTitle.replace("'", "\\'");
        return "import 'package:flutter/material.dart';\n"
                + "import '../constants/app_colors.dart';\n"
                + "import '../widgets/custom_button.dart';\n\n"
                + "class HomeScreen extends StatefulWidget {\n"
                + "  const HomeScreen({super.key, required this.title});\n\n"
                + "  final String title;\n\n"
                + "  @override\n"
                + "  State<HomeScreen> createState() => _HomeScreenState();\n"
                + "}\n\n"
                + "class _HomeScreenState extends State<HomeScreen> {\n"
                + "  int _counter = 0;\n\n"
                + "  void _incrementCounter() {\n"
                + "    setState(() {\n"
                + "      _counter++;\n"
                + "    });\n"
                + "  }\n\n"
                + "  @override\n"
                + "  Widget build(BuildContext context) {\n"
                + "    return Scaffold(\n"
                + "      appBar: AppBar(\n"
                + "        backgroundColor: Theme.of(context).colorScheme.inversePrimary,\n"
                + "        title: Text(widget.title),\n"
                + "      ),\n"
                + "      body: Center(\n"
                + "        child: Column(\n"
                + "          mainAxisAlignment: MainAxisAlignment.center,\n"
                + "          children: <Widget>[\n"
                + "            const Text(\n"
                + "              'Welcome to your Flutter project!',\n"
                + "              style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),\n"
                + "            ),\n"
                + "            const SizedBox(height: 16),\n"
                + "            const Text('You have pushed the button this many times:'),\n"
                + "            Text(\n"
                + "              '$_counter',\n"
                + "              style: Theme.of(context).textTheme.headlineMedium?.copyWith(\n"
                + "                    color: AppColors.primary,\n"
                + "                    fontWeight: FontWeight.bold,\n"
                + "                  ),\n"
                + "            ),\n"
                + "            const SizedBox(height: 24),\n"
                + "            CustomButton(\n"
                + "              label: 'Increment',\n"
                + "              icon: Icons.add,\n"
                + "              onPressed: _incrementCounter,\n"
                + "            ),\n"
                + "          ],\n"
                + "        ),\n"
                + "      ),\n"
                + "      floatingActionButton: FloatingActionButton(\n"
                + "        onPressed: _incrementCounter,\n"
                + "        tooltip: 'Increment',\n"
                + "        child: const Icon(Icons.add),\n"
                + "      ),\n"
                + "    );\n"
                + "  }\n"
                + "}\n";
    }

    public static String buildFlutterCustomButtonDart() {
        return "import 'package:flutter/material.dart';\n\n"
                + "class CustomButton extends StatelessWidget {\n"
                + "  final String label;\n"
                + "  final VoidCallback onPressed;\n"
                + "  final IconData? icon;\n\n"
                + "  const CustomButton({\n"
                + "    super.key,\n"
                + "    required this.label,\n"
                + "    required this.onPressed,\n"
                + "    this.icon,\n"
                + "  });\n\n"
                + "  @override\n"
                + "  Widget build(BuildContext context) {\n"
                + "    if (icon != null) {\n"
                + "      return FilledButton.icon(\n"
                + "        onPressed: onPressed,\n"
                + "        icon: Icon(icon),\n"
                + "        label: Text(label),\n"
                + "      );\n"
                + "    }\n"
                + "    return FilledButton(\n"
                + "      onPressed: onPressed,\n"
                + "      child: Text(label),\n"
                + "    );\n"
                + "  }\n"
                + "}\n";
    }

    public static String buildFlutterAppColorsDart() {
        return "import 'package:flutter/material.dart';\n\n"
                + "class AppColors {\n"
                + "  static const Color primary = Colors.deepPurple;\n"
                + "  static const Color secondary = Colors.amber;\n"
                + "  static const Color background = Color(0xFFF8F9FA);\n"
                + "}\n";
    }

    public static String buildFlutterItemModelDart() {
        return "class ItemModel {\n"
                + "  final String id;\n"
                + "  final String title;\n"
                + "  final bool isCompleted;\n\n"
                + "  const ItemModel({\n"
                + "    required this.id,\n"
                + "    required this.title,\n"
                + "    this.isCompleted = false,\n"
                + "  });\n"
                + "}\n";
    }

    private static String buildPubspecYaml(String cleanAppName) {
        return "name: " + cleanAppName + "\n"
                + "description: \"A new Flutter project created with Sumer Studio.\"\n"
                + "publish_to: 'none'\n"
                + "version: 1.0.0+1\n\n"
                + "environment:\n"
                + "  sdk: ^3.5.0\n\n"
                + "dependencies:\n"
                + "  flutter:\n"
                + "    sdk: flutter\n"
                + "  cupertino_icons: ^1.0.8\n\n"
                + "dev_dependencies:\n"
                + "  flutter_test:\n"
                + "    sdk: flutter\n"
                + "  flutter_lints: ^4.0.0\n\n"
                + "flutter:\n"
                + "  uses-material-design: true\n"
                + "  assets:\n"
                + "    - assets/images/\n"
                + "    - assets/fonts/\n";
    }

    private static String buildAnalysisOptionsYaml() {
        return "include: package:flutter_lints/flutter.yaml\n\n"
                + "linter:\n"
                + "  rules:\n"
                + "    prefer_const_constructors: true\n"
                + "    prefer_const_literals_to_create_immutables: true\n";
    }
}
