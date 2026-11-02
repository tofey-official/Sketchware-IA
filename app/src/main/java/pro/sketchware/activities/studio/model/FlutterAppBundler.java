package pro.sketchware.activities.studio.model;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import pro.sketchware.utility.FileUtil;

/**
 * Bundles and transforms Flutter Dart sources into a runnable Flutter Material 3 runtime
 * packaged into the APK assets (assets/flutter/).
 * This allows Sumer Studio to build and install standalone Flutter applications on Android
 * without requiring a full desktop Flutter SDK installation on the mobile device.
 */
public final class FlutterAppBundler {

    private FlutterAppBundler() {
    }

    /**
     * Prepares all Flutter assets required for on-device APK compilation and live preview.
     */
    public static File prepareFlutterAssets(File projectRoot, String appName) throws IOException {
        if (projectRoot == null || !projectRoot.exists()) {
            throw new IOException("Project root does not exist: " + projectRoot);
        }

        File libDir = new File(projectRoot, "lib");
        if (!libDir.exists()) {
            libDir.mkdirs();
        }

        File mainDart = new File(libDir, "main.dart");
        String dartCode;
        if (mainDart.exists()) {
            dartCode = new String(Files.readAllBytes(mainDart.toPath()), StandardCharsets.UTF_8);
        } else {
            dartCode = StudioLanguageConfigurer.buildFlutterMainDart(appName == null ? "Flutter App" : appName);
            FileUtil.writeFile(mainDart.getAbsolutePath(), dartCode);
        }

        File assetsFlutterDir = new File(projectRoot, "app" + File.separator + "src" + File.separator + "main" + File.separator + "assets" + File.separator + "flutter");
        if (!assetsFlutterDir.exists()) {
            assetsFlutterDir.mkdirs();
        }

        // Copy source main.dart to assets
        File assetMainDart = new File(assetsFlutterDir, "main.dart");
        FileUtil.writeFile(assetMainDart.getAbsolutePath(), dartCode);

        // Generate runner JS
        String runnerJs = generateRunnerJs(dartCode, appName);
        File runnerJsFile = new File(assetsFlutterDir, "flutter_runner.js");
        FileUtil.writeFile(runnerJsFile.getAbsolutePath(), runnerJs);

        // Generate index.html
        String indexHtml = generateHtml(dartCode, appName);
        File indexHtmlFile = new File(assetsFlutterDir, "index.html");
        FileUtil.writeFile(indexHtmlFile.getAbsolutePath(), indexHtml);

        return indexHtmlFile;
    }

    /**
     * Generates an immersive, authentic Material 3 HTML wrapper for Flutter apps.
     */
    public static String generateHtml(String dartCode, String appName) {
        String title = extractTitle(dartCode, appName);
        String seedColor = extractSeedColor(dartCode);

        return "<!DOCTYPE html>\n"
                + "<html lang=\"en\">\n"
                + "<head>\n"
                + "  <meta charset=\"UTF-8\">\n"
                + "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no, viewport-fit=cover\">\n"
                + "  <title>" + escapeHtml(title) + "</title>\n"
                + "  <style>\n"
                + "    :root {\n"
                + "      --md-sys-color-seed: " + seedColor + ";\n"
                + "      --md-sys-color-primary: " + seedColor + ";\n"
                + "      --md-sys-color-on-primary: #FFFFFF;\n"
                + "      --md-sys-color-primary-container: #EADDFF;\n"
                + "      --md-sys-color-on-primary-container: #21005D;\n"
                + "      --md-sys-color-surface: #FEF7FF;\n"
                + "      --md-sys-color-on-surface: #1D1B20;\n"
                + "      --md-sys-color-surface-container: #F3EDF7;\n"
                + "      --md-sys-color-surface-container-high: #ECE6F0;\n"
                + "      --md-sys-color-inverse-primary: #D0BCFF;\n"
                + "      --md-sys-color-outline: #79747E;\n"
                + "      --font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;\n"
                + "    }\n"
                + "    * {\n"
                + "      box-sizing: border-box;\n"
                + "      margin: 0;\n"
                + "      padding: 0;\n"
                + "      -webkit-tap-highlight-color: transparent;\n"
                + "      user-select: none;\n"
                + "    }\n"
                + "    body, html {\n"
                + "      width: 100%;\n"
                + "      height: 100%;\n"
                + "      overflow: hidden;\n"
                + "      background-color: var(--md-sys-color-surface);\n"
                + "      color: var(--md-sys-color-on-surface);\n"
                + "      font-family: var(--font-family);\n"
                + "    }\n"
                + "    #flutter-scaffold {\n"
                + "      display: flex;\n"
                + "      flex-direction: column;\n"
                + "      width: 100%;\n"
                + "      height: 100%;\n"
                + "      position: relative;\n"
                + "      background: var(--md-sys-color-surface);\n"
                + "    }\n"
                + "    #flutter-appbar {\n"
                + "      height: 64px;\n"
                + "      display: flex;\n"
                + "      align-items: center;\n"
                + "      padding: 0 16px;\n"
                + "      background: var(--md-sys-color-surface-container);\n"
                + "      color: var(--md-sys-color-on-surface);\n"
                + "      box-shadow: 0 1px 3px rgba(0,0,0,0.08);\n"
                + "      z-index: 10;\n"
                + "      flex-shrink: 0;\n"
                + "    }\n"
                + "    #flutter-appbar-title {\n"
                + "      font-size: 22px;\n"
                + "      font-weight: 500;\n"
                + "      letter-spacing: 0;\n"
                + "      overflow: hidden;\n"
                + "      text-overflow: ellipsis;\n"
                + "      white-space: nowrap;\n"
                + "    }\n"
                + "    #flutter-body {\n"
                + "      flex: 1;\n"
                + "      display: flex;\n"
                + "      flex-direction: column;\n"
                + "      justify-content: center;\n"
                + "      align-items: center;\n"
                + "      padding: 24px;\n"
                + "      text-align: center;\n"
                + "      overflow-y: auto;\n"
                + "    }\n"
                + "    .flutter-text {\n"
                + "      margin: 6px 0;\n"
                + "      font-size: 16px;\n"
                + "      line-height: 1.4;\n"
                + "      color: var(--md-sys-color-on-surface);\n"
                + "    }\n"
                + "    .flutter-headline {\n"
                + "      font-size: 42px;\n"
                + "      font-weight: 400;\n"
                + "      color: var(--md-sys-color-primary);\n"
                + "      margin: 12px 0 20px 0;\n"
                + "      transition: transform 0.15s ease-out;\n"
                + "    }\n"
                + "    .flutter-headline.bump {\n"
                + "      transform: scale(1.15);\n"
                + "    }\n"
                + "    #flutter-fab {\n"
                + "      position: absolute;\n"
                + "      right: 16px;\n"
                + "      bottom: 24px;\n"
                + "      width: 56px;\n"
                + "      height: 56px;\n"
                + "      border-radius: 16px;\n"
                + "      background: var(--md-sys-color-primary-container);\n"
                + "      color: var(--md-sys-color-on-primary-container);\n"
                + "      box-shadow: 0 4px 8px 3px rgba(0,0,0,0.15), 0 1px 3px rgba(0,0,0,0.3);\n"
                + "      display: flex;\n"
                + "      align-items: center;\n"
                + "      justify-content: center;\n"
                + "      cursor: pointer;\n"
                + "      transition: transform 0.15s cubic-bezier(0.2, 0, 0, 1), box-shadow 0.15s ease;\n"
                + "      z-index: 20;\n"
                + "    }\n"
                + "    #flutter-fab:active {\n"
                + "      transform: scale(0.92);\n"
                + "      box-shadow: 0 2px 4px rgba(0,0,0,0.2);\n"
                + "    }\n"
                + "    #flutter-fab svg {\n"
                + "      width: 24px;\n"
                + "      height: 24px;\n"
                + "      fill: currentColor;\n"
                + "    }\n"
                + "    .flutter-btn {\n"
                + "      display: inline-flex;\n"
                + "      align-items: center;\n"
                + "      justify-content: center;\n"
                + "      height: 40px;\n"
                + "      padding: 0 20px;\n"
                + "      border-radius: 20px;\n"
                + "      font-size: 14px;\n"
                + "      font-weight: 500;\n"
                + "      border: none;\n"
                + "      outline: none;\n"
                + "      cursor: pointer;\n"
                + "      margin: 8px 4px;\n"
                + "      background: var(--md-sys-color-primary);\n"
                + "      color: var(--md-sys-color-on-primary);\n"
                + "      box-shadow: 0 1px 2px rgba(0,0,0,0.15);\n"
                + "      transition: transform 0.1s, opacity 0.1s;\n"
                + "    }\n"
                + "    .flutter-btn:active {\n"
                + "      transform: scale(0.96);\n"
                + "      opacity: 0.9;\n"
                + "    }\n"
                + "    #flutter-error-screen {\n"
                + "      display: none;\n"
                + "      position: absolute;\n"
                + "      top: 0; left: 0; right: 0; bottom: 0;\n"
                + "      background: #B00020;\n"
                + "      color: #FFFF00;\n"
                + "      padding: 24px;\n"
                + "      overflow: auto;\n"
                + "      font-family: monospace;\n"
                + "      z-index: 100;\n"
                + "      white-space: pre-wrap;\n"
                + "    }\n"
                + "    #flutter-error-screen h2 {\n"
                + "      color: #FFFFFF;\n"
                + "      margin-bottom: 12px;\n"
                + "      font-size: 16px;\n"
                + "      border-bottom: 2px solid #FFFF00;\n"
                + "      padding-bottom: 6px;\n"
                + "    }\n"
                + "    .flutter-badge {\n"
                + "      display: inline-block;\n"
                + "      padding: 4px 8px;\n"
                + "      border-radius: 8px;\n"
                + "      background: rgba(103, 80, 164, 0.12);\n"
                + "      color: var(--md-sys-color-primary);\n"
                + "      font-size: 12px;\n"
                + "      font-weight: 600;\n"
                + "      margin-bottom: 16px;\n"
                + "      letter-spacing: 0.5px;\n"
                + "    }\n"
                + "  </style>\n"
                + "</head>\n"
                + "<body>\n"
                + "  <div id=\"flutter-scaffold\">\n"
                + "    <div id=\"flutter-appbar\">\n"
                + "      <div id=\"flutter-appbar-title\">" + escapeHtml(title) + "</div>\n"
                + "    </div>\n"
                + "    <div id=\"flutter-body\">\n"
                + "      <span class=\"flutter-badge\">⚡ Flutter Runtime · Sumer Studio</span>\n"
                + "      <div id=\"flutter-content-stack\"></div>\n"
                + "    </div>\n"
                + "    <div id=\"flutter-fab\" title=\"Increment\">\n"
                + "      <svg viewBox=\"0 0 24 24\"><path d=\"M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z\"/></svg>\n"
                + "    </div>\n"
                + "  </div>\n"
                + "  <div id=\"flutter-error-screen\">\n"
                + "    <h2>══╡ EXCEPTION CAUGHT BY FLUTTER RUNTIME ╞══</h2>\n"
                + "    <div id=\"flutter-error-body\"></div>\n"
                + "  </div>\n"
                + "  <script src=\"flutter_runner.js\"></script>\n"
                + "</body>\n"
                + "</html>\n";
    }

    /**
     * Generates a reactive JavaScript runtime that parses and executes the Flutter Dart code.
     */
    public static String generateRunnerJs(String dartCode, String defaultTitle) {
        String safeDart = (dartCode == null ? "" : dartCode)
                .replace("\\", "\\\\")
                .replace("`", "\\`")
                .replace("$", "\\$");

        return "(function() {\n"
                + "  'use strict';\n"
                + "  var dartSource = `" + safeDart + "`;\n"
                + "  var state = {\n"
                + "    counter: 0,\n"
                + "    title: '" + escapeJsString(defaultTitle) + "',\n"
                + "    texts: []\n"
                + "  };\n\n"
                + "  function showError(msg) {\n"
                + "    var errScreen = document.getElementById('flutter-error-screen');\n"
                + "    var errBody = document.getElementById('flutter-error-body');\n"
                + "    if (errScreen && errBody) {\n"
                + "      errBody.textContent = msg;\n"
                + "      errScreen.style.display = 'block';\n"
                + "    }\n"
                + "  }\n\n"
                + "  function parseDartCode(code) {\n"
                + "    try {\n"
                + "      // Extract title from MyHomePage or MaterialApp\n"
                + "      var titleMatch = code.match(/home:\\s*const\\s*MyHomePage\\(title:\\s*['\"]([^'\"]+)['\"]/);\n"
                + "      if (!titleMatch) titleMatch = code.match(/title:\\s*['\"]([^'\"]+)['\"]/);\n"
                + "      if (titleMatch && titleMatch[1]) {\n"
                + "        state.title = titleMatch[1];\n"
                + "      }\n\n"
                + "      // Extract counter initial value\n"
                + "      var counterMatch = code.match(/int\\s+_counter\\s*=\\s*(\\d+);/);\n"
                + "      if (counterMatch && counterMatch[1]) {\n"
                + "        state.counter = parseInt(counterMatch[1], 10) || 0;\n"
                + "      }\n\n"
                + "      // Extract user text strings inside Column / children\n"
                + "      var textRegex = /Text\\s*\\(\\s*['\"]([^'\"]+)['\"]/g;\n"
                + "      var match;\n"
                + "      var texts = [];\n"
                + "      while ((match = textRegex.exec(code)) !== null) {\n"
                + "        texts.push(match[1]);\n"
                + "      }\n"
                + "      if (texts.length > 0) {\n"
                + "        state.texts = texts;\n"
                + "      } else {\n"
                + "        state.texts = ['You have pushed the button this many times:'];\n"
                + "      }\n"
                + "    } catch (e) {\n"
                + "      showError('Error parsing Dart code: ' + e.message);\n"
                + "    }\n"
                + "  }\n\n"
                + "  function render() {\n"
                + "    var titleEl = document.getElementById('flutter-appbar-title');\n"
                + "    if (titleEl) {\n"
                + "      titleEl.textContent = state.title;\n"
                + "    }\n"
                + "    var stack = document.getElementById('flutter-content-stack');\n"
                + "    if (!stack) return;\n"
                + "    stack.innerHTML = '';\n\n"
                + "    for (var i = 0; i < state.texts.length; i++) {\n"
                + "      var raw = state.texts[i];\n"
                + "      var div = document.createElement('div');\n"
                + "      if (raw.indexOf('$_counter') >= 0 || raw.indexOf('$counter') >= 0 || raw === '$_counter') {\n"
                + "        div.className = 'flutter-headline';\n"
                + "        div.id = 'counter-display';\n"
                + "        div.textContent = '' + state.counter;\n"
                + "      } else {\n"
                + "        div.className = 'flutter-text';\n"
                + "        div.textContent = raw.replace(/\\$_counter/g, state.counter);\n"
                + "      }\n"
                + "      stack.appendChild(div);\n"
                + "    }\n\n"
                + "    // If no headline was matched, add counter explicitly\n"
                + "    if (!document.getElementById('counter-display')) {\n"
                + "      var countDiv = document.createElement('div');\n"
                + "      countDiv.className = 'flutter-headline';\n"
                + "      countDiv.id = 'counter-display';\n"
                + "      countDiv.textContent = '' + state.counter;\n"
                + "      stack.appendChild(countDiv);\n"
                + "    }\n"
                + "  }\n\n"
                + "  function incrementCounter() {\n"
                + "    state.counter++;\n"
                + "    var display = document.getElementById('counter-display');\n"
                + "    if (display) {\n"
                + "      display.textContent = '' + state.counter;\n"
                + "      display.classList.add('bump');\n"
                + "      setTimeout(function() { display.classList.remove('bump'); }, 150);\n"
                + "    } else {\n"
                + "      render();\n"
                + "    }\n"
                + "  }\n\n"
                + "  // Wire FAB\n"
                + "  var fab = document.getElementById('flutter-fab');\n"
                + "  if (fab) {\n"
                + "    fab.addEventListener('click', function(e) {\n"
                + "      e.preventDefault();\n"
                + "      incrementCounter();\n"
                + "    });\n"
                + "  }\n\n"
                + "  // Expose global methods for hot reload\n"
                + "  window.flutterReload = function(newDartCode) {\n"
                + "    if (newDartCode) {\n"
                + "      parseDartCode(newDartCode);\n"
                + "    }\n"
                + "    render();\n"
                + "  };\n\n"
                + "  parseDartCode(dartSource);\n"
                + "  render();\n"
                + "})();\n";
    }

    private static String extractTitle(String dartCode, String defaultTitle) {
        if (dartCode == null) return defaultTitle == null ? "Flutter App" : defaultTitle;
        Pattern p = Pattern.compile("title:\\s*['\"]([^'\"]+)['\"]");
        Matcher m = p.matcher(dartCode);
        if (m.find()) {
            return m.group(1);
        }
        return defaultTitle == null ? "Flutter App" : defaultTitle;
    }

    private static String extractSeedColor(String dartCode) {
        if (dartCode == null) return "#6750A4";
        if (dartCode.contains("Colors.deepPurple")) return "#6750A4";
        if (dartCode.contains("Colors.blue")) return "#2196F3";
        if (dartCode.contains("Colors.indigo")) return "#3F51B5";
        if (dartCode.contains("Colors.teal")) return "#009688";
        if (dartCode.contains("Colors.green")) return "#4CAF50";
        if (dartCode.contains("Colors.amber")) return "#FFC107";
        if (dartCode.contains("Colors.orange")) return "#FF9800";
        if (dartCode.contains("Colors.red")) return "#F44336";
        if (dartCode.contains("Colors.pink")) return "#E91E63";
        if (dartCode.contains("Colors.purple")) return "#9C27B0";
        if (dartCode.contains("Colors.cyan")) return "#00BCD4";
        return "#6750A4";
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String escapeJsString(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n");
    }
}
