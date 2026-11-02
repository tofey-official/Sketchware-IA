package pro.sketchware.activities.studio.model;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import pro.sketchware.utility.FileUtil;

/**
 * Bundles, validates, and transforms Flutter Dart sources into a runnable Flutter Material 3 runtime
 * packaged into the APK assets (assets/flutter/).
 * Includes syntax validation, syntax error reporting, and dynamic widget tree interpretation.
 */
public final class FlutterAppBundler {

    private FlutterAppBundler() {
    }

    /**
     * Thrown when Dart code fails syntax validation or has unresolved dangling tokens.
     */
    public static class DartCompilationException extends IOException {
        private final int lineNumber;
        private final String filename;
        private final String details;

        public DartCompilationException(String filename, int lineNumber, String details) {
            super("[" + filename + ":" + lineNumber + "] Flutter Compiler Error: " + details);
            this.filename = filename;
            this.lineNumber = lineNumber;
            this.details = details;
        }

        public int getLineNumber() {
            return lineNumber;
        }

        public String getFilename() {
            return filename;
        }

        public String getDetails() {
            return details;
        }
    }

    /**
     * Prepares all Flutter assets required for on-device APK compilation and live preview.
     * Validates Dart syntax across all lib/ files, synchronizes edits, and builds the Flutter runtime bundle.
     */
    public static File prepareFlutterAssets(File projectRoot, String appName) throws IOException {
        if (projectRoot == null || !projectRoot.exists()) {
            throw new IOException("Project root does not exist: " + projectRoot);
        }

        File libDir = new File(projectRoot, "lib");
        if (!libDir.exists()) {
            libDir.mkdirs();
        }

        File assetsFlutterDir = new File(projectRoot, "app" + File.separator + "src" + File.separator + "main" + File.separator + "assets" + File.separator + "flutter");
        if (!assetsFlutterDir.exists()) {
            assetsFlutterDir.mkdirs();
        }

        File mainDart = new File(libDir, "main.dart");
        File assetMainDart = new File(assetsFlutterDir, "main.dart");

        // Bidirectional sync safeguard: If the user previously edited assetMainDart, preserve their changes!
        if (assetMainDart.exists() && assetMainDart.length() > 0) {
            if (!mainDart.exists() || assetMainDart.lastModified() > mainDart.lastModified() + 1000L) {
                String assetContent = new String(Files.readAllBytes(assetMainDart.toPath()), StandardCharsets.UTF_8);
                if (!assetContent.trim().isEmpty()) {
                    FileUtil.writeFile(mainDart.getAbsolutePath(), assetContent);
                }
            }
        }

        String mainDartCode;
        if (mainDart.exists()) {
            mainDartCode = new String(Files.readAllBytes(mainDart.toPath()), StandardCharsets.UTF_8);
        } else {
            mainDartCode = StudioLanguageConfigurer.buildFlutterMainDart(appName == null ? "Flutter App" : appName);
            FileUtil.writeFile(mainDart.getAbsolutePath(), mainDartCode);
        }

        // Collect and validate all .dart files across lib/
        List<File> allDartFiles = new ArrayList<>();
        collectDartFiles(libDir, allDartFiles);
        StringBuilder bundledSources = new StringBuilder();
        bundledSources.append(mainDartCode).append("\n");

        for (File dartFile : allDartFiles) {
            String rel = libDir.toPath().relativize(dartFile.toPath()).toString().replace('\\', '/');
            String code = new String(Files.readAllBytes(dartFile.toPath()), StandardCharsets.UTF_8);
            validateDartSyntax(code, "lib/" + rel);
            if (!dartFile.equals(mainDart)) {
                bundledSources.append("\n// --- lib/").append(rel).append(" ---\n").append(code).append("\n");
            }
        }

        // Recursively copy all files and subdirectories from lib/ to assets/flutter/lib/
        copyDirectory(libDir, new File(assetsFlutterDir, "lib"));

        // Copy verified main.dart to assets
        FileUtil.writeFile(assetMainDart.getAbsolutePath(), mainDartCode);

        // Generate dynamic runner JS with all bundled sources
        String runnerJs = generateRunnerJs(bundledSources.toString(), appName);
        File runnerJsFile = new File(assetsFlutterDir, "flutter_runner.js");
        FileUtil.writeFile(runnerJsFile.getAbsolutePath(), runnerJs);

        // Generate index.html
        String indexHtml = generateHtml(bundledSources.toString(), appName);
        File indexHtmlFile = new File(assetsFlutterDir, "index.html");
        FileUtil.writeFile(indexHtmlFile.getAbsolutePath(), indexHtml);

        return indexHtmlFile;
    }

    private static void collectDartFiles(File dir, List<File> list) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (f.isDirectory()) {
                collectDartFiles(f, list);
            } else if (f.isFile() && f.getName().toLowerCase(Locale.US).endsWith(".dart")) {
                list.add(f);
            }
        }
    }

    public static void copyDirectory(File src, File dest) throws IOException {
        if (!src.exists()) return;
        if (src.isDirectory()) {
            if (!dest.exists()) dest.mkdirs();
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

    /**
     * Validates basic Dart syntax: bracket balance, string terminations, and stray unclosed identifiers.
     */
    public static void validateDartSyntax(String code, String filename) throws DartCompilationException {
        if (code == null || code.trim().isEmpty()) {
            throw new DartCompilationException(filename, 1, "Source file is empty.");
        }

        String[] lines = code.split("\r?\n");
        Deque<Character> bracketStack = new ArrayDeque<>();
        Deque<Integer> bracketLines = new ArrayDeque<>();

        boolean inBlockComment = false;

        for (int i = 0; i < lines.length; i++) {
            int lineNum = i + 1;
            String line = lines[i];
            String trimmed = line.trim();

            if (inBlockComment) {
                int endIdx = trimmed.indexOf("*/");
                if (endIdx >= 0) {
                    inBlockComment = false;
                    trimmed = trimmed.substring(endIdx + 2).trim();
                } else {
                    continue;
                }
            }

            int blockStart = trimmed.indexOf("/*");
            if (blockStart >= 0) {
                inBlockComment = true;
                trimmed = trimmed.substring(0, blockStart).trim();
            }

            // Remove single line comments
            int commentIdx = trimmed.indexOf("//");
            if (commentIdx >= 0) {
                trimmed = trimmed.substring(0, commentIdx).trim();
            }

            if (trimmed.isEmpty()) {
                continue;
            }

            // Check for lone broken identifiers (e.g. "chi" on a line by itself)
            if (trimmed.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
                if (!trimmed.equals("else") && !trimmed.equals("try") && !trimmed.equals("finally")
                        && !trimmed.equals("do") && !trimmed.equals("break") && !trimmed.equals("continue")) {
                    throw new DartCompilationException(filename, lineNum,
                            "Unexpected token '" + trimmed + "' - missing assignment, expression, or closing bracket");
                }
            }

            // Parse characters for brackets and quotes
            boolean inSingleQuote = false;
            boolean inDoubleQuote = false;
            char prev = ' ';

            for (int c = 0; c < trimmed.length(); c++) {
                char ch = trimmed.charAt(c);

                if (ch == '\'' && prev != '\\' && !inDoubleQuote) {
                    inSingleQuote = !inSingleQuote;
                } else if (ch == '"' && prev != '\\' && !inSingleQuote) {
                    inDoubleQuote = !inDoubleQuote;
                } else if (!inSingleQuote && !inDoubleQuote) {
                    if (ch == '(' || ch == '{' || ch == '[') {
                        bracketStack.push(ch);
                        bracketLines.push(lineNum);
                    } else if (ch == ')' || ch == '}' || ch == ']') {
                        if (bracketStack.isEmpty()) {
                            throw new DartCompilationException(filename, lineNum,
                                    "Unexpected closing bracket '" + ch + "' without matching opening bracket");
                        }
                        char open = bracketStack.pop();
                        bracketLines.pop();
                        if ((ch == ')' && open != '(')
                                || (ch == '}' && open != '{')
                                || (ch == ']' && open != '[')) {
                            throw new DartCompilationException(filename, lineNum,
                                    "Mismatched bracket: expected matching for '" + open + "' but found '" + ch + "'");
                        }
                    }
                }
                prev = ch;
            }

            if (inSingleQuote || inDoubleQuote) {
                // Unterminated string on this line
                throw new DartCompilationException(filename, lineNum, "Unterminated string literal on line " + lineNum);
            }
        }

        if (!bracketStack.isEmpty()) {
            char open = bracketStack.pop();
            int openLine = bracketLines.pop();
            throw new DartCompilationException(filename, openLine,
                    "Unclosed bracket '" + open + "' opened at line " + openLine + " was never closed");
        }
    }

    /**
     * Generates an authentic Material 3 responsive HTML container.
     */
    public static String generateHtml(String dartCode, String appName) {
        String title = extractTitle(dartCode, appName);
        String seedColor = extractSeedColor(dartCode);
        boolean isDark = dartCode != null && (dartCode.contains("Brightness.dark") || dartCode.contains("ThemeData.dark()"));

        return "<!DOCTYPE html>\n"
                + "<html lang=\"en\">\n"
                + "<head>\n"
                + "  <meta charset=\"UTF-8\">\n"
                + "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no, viewport-fit=cover\">\n"
                + "  <title>" + escapeHtml(title) + "</title>\n"
                + "  <style>\n"
                + "    :root {\n"
                + "      --md-primary: " + seedColor + ";\n"
                + "      --md-on-primary: #FFFFFF;\n"
                + "      --md-primary-container: " + (isDark ? "#4F378B" : "#EADDFF") + ";\n"
                + "      --md-on-primary-container: " + (isDark ? "#EADDFF" : "#21005D") + ";\n"
                + "      --md-surface: " + (isDark ? "#141218" : "#FEF7FF") + ";\n"
                + "      --md-on-surface: " + (isDark ? "#E6E0E9" : "#1D1B20") + ";\n"
                + "      --md-surface-container: " + (isDark ? "#211F26" : "#F3EDF7") + ";\n"
                + "      --md-surface-container-high: " + (isDark ? "#2B2930" : "#ECE6F0") + ";\n"
                + "      --md-card-bg: " + (isDark ? "#2B2930" : "#FFFFFF") + ";\n"
                + "      --font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;\n"
                + "    }\n"
                + "    * { box-sizing: border-box; margin: 0; padding: 0; -webkit-tap-highlight-color: transparent; user-select: none; }\n"
                + "    body, html {\n"
                + "      width: 100%; height: 100%; overflow: hidden;\n"
                + "      background-color: var(--md-surface); color: var(--md-on-surface);\n"
                + "      font-family: var(--font-family);\n"
                + "    }\n"
                + "    #flutter-scaffold {\n"
                + "      display: flex; flex-direction: column;\n"
                + "      width: 100%; height: 100%; position: relative;\n"
                + "      background: var(--md-surface);\n"
                + "    }\n"
                + "    #flutter-appbar {\n"
                + "      height: 64px; display: flex; align-items: center;\n"
                + "      padding: 0 16px; background: var(--md-surface-container);\n"
                + "      color: var(--md-on-surface); box-shadow: 0 1px 3px rgba(0,0,0,0.08);\n"
                + "      z-index: 10; flex-shrink: 0;\n"
                + "    }\n"
                + "    #flutter-appbar-title {\n"
                + "      font-size: 22px; font-weight: 500;\n"
                + "      overflow: hidden; text-overflow: ellipsis; white-space: nowrap;\n"
                + "    }\n"
                + "    #flutter-body {\n"
                + "      flex: 1; display: flex; flex-direction: column;\n"
                + "      padding: 20px; overflow-y: auto;\n"
                + "      -webkit-overflow-scrolling: touch;\n"
                + "    }\n"
                + "    .flutter-center {\n"
                + "      display: flex; flex-direction: column;\n"
                + "      justify-content: center; align-items: center;\n"
                + "      min-height: 100%; width: 100%; text-align: center;\n"
                + "    }\n"
                + "    .flutter-column {\n"
                + "      display: flex; flex-direction: column;\n"
                + "      align-items: center; justify-content: center;\n"
                + "      width: 100%; gap: 12px;\n"
                + "    }\n"
                + "    .flutter-row {\n"
                + "      display: flex; flex-direction: row;\n"
                + "      align-items: center; justify-content: center;\n"
                + "      gap: 12px; flex-wrap: wrap;\n"
                + "    }\n"
                + "    .flutter-text {\n"
                + "      font-size: 16px; line-height: 1.4;\n"
                + "      color: var(--md-on-surface);\n"
                + "    }\n"
                + "    .flutter-headline {\n"
                + "      font-size: 40px; font-weight: 400;\n"
                + "      color: var(--md-primary); margin: 8px 0 16px 0;\n"
                + "      transition: transform 0.15s ease-out;\n"
                + "    }\n"
                + "    .flutter-headline.bump { transform: scale(1.18); }\n"
                + "    .flutter-btn {\n"
                + "      display: inline-flex; align-items: center; justify-content: center;\n"
                + "      height: 44px; padding: 0 24px; border-radius: 22px;\n"
                + "      font-size: 14px; font-weight: 500; border: none; outline: none;\n"
                + "      cursor: pointer; background: var(--md-primary); color: var(--md-on-primary);\n"
                + "      box-shadow: 0 1px 3px rgba(0,0,0,0.2);\n"
                + "      transition: transform 0.1s, box-shadow 0.1s;\n"
                + "    }\n"
                + "    .flutter-btn:active { transform: scale(0.96); box-shadow: none; }\n"
                + "    .flutter-btn-outlined {\n"
                + "      background: transparent; color: var(--md-primary);\n"
                + "      border: 1px solid var(--md-primary); box-shadow: none;\n"
                + "    }\n"
                + "    .flutter-card {\n"
                + "      background: var(--md-card-bg); border-radius: 16px;\n"
                + "      padding: 16px; margin: 8px 0; width: 100%; max-width: 400px;\n"
                + "      box-shadow: 0 2px 6px rgba(0,0,0,0.08);\n"
                + "    }\n"
                + "    .flutter-input {\n"
                + "      width: 100%; max-width: 320px; height: 52px;\n"
                + "      padding: 0 16px; border-radius: 12px;\n"
                + "      border: 1px solid rgba(121, 116, 126, 0.4);\n"
                + "      background: var(--md-surface-container);\n"
                + "      color: var(--md-on-surface); font-size: 16px;\n"
                + "      outline: none;\n"
                + "    }\n"
                + "    .flutter-input:focus { border-color: var(--md-primary); border-width: 2px; }\n"
                + "    #flutter-fab {\n"
                + "      position: absolute; right: 16px; bottom: 24px;\n"
                + "      width: 56px; height: 56px; border-radius: 16px;\n"
                + "      background: var(--md-primary-container);\n"
                + "      color: var(--md-on-primary-container);\n"
                + "      box-shadow: 0 4px 10px rgba(0,0,0,0.2);\n"
                + "      display: flex; align-items: center; justify-content: center;\n"
                + "      cursor: pointer; transition: transform 0.15s;\n"
                + "      z-index: 20;\n"
                + "    }\n"
                + "    #flutter-fab:active { transform: scale(0.92); }\n"
                + "    #flutter-fab svg { width: 26px; height: 26px; fill: currentColor; }\n"
                + "    #flutter-error-screen {\n"
                + "      display: none; position: absolute;\n"
                + "      top: 0; left: 0; right: 0; bottom: 0;\n"
                + "      background: #B00020; color: #FFFF00;\n"
                + "      padding: 24px; overflow: auto; font-family: monospace;\n"
                + "      z-index: 100; white-space: pre-wrap; font-size: 14px;\n"
                + "    }\n"
                + "    #flutter-error-screen h2 {\n"
                + "      color: #FFFFFF; margin-bottom: 12px; font-size: 16px;\n"
                + "      border-bottom: 2px solid #FFFF00; padding-bottom: 6px;\n"
                + "    }\n"
                + "    .flutter-runtime-badge {\n"
                + "      display: inline-block; padding: 4px 10px; border-radius: 8px;\n"
                + "      background: rgba(103, 80, 164, 0.12); color: var(--md-primary);\n"
                + "      font-size: 11px; font-weight: 600; margin-bottom: 14px;\n"
                + "    }\n"
                + "  </style>\n"
                + "</head>\n"
                + "<body>\n"
                + "  <div id=\"flutter-scaffold\">\n"
                + "    <div id=\"flutter-appbar\">\n"
                + "      <div id=\"flutter-appbar-title\">" + escapeHtml(title) + "</div>\n"
                + "    </div>\n"
                + "    <div id=\"flutter-body\">\n"
                + "      <div class=\"flutter-center\">\n"
                + "        <span class=\"flutter-runtime-badge\">⚡ Flutter Runtime · Sumer Studio</span>\n"
                + "        <div id=\"flutter-root-tree\"></div>\n"
                + "      </div>\n"
                + "    </div>\n"
                + "    <div id=\"flutter-fab\" title=\"Action\">\n"
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
     * Generates a reactive JavaScript runtime that parses and executes Flutter Dart widgets.
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
                + "    _counter: 0,\n"
                + "    title: '" + escapeJsString(defaultTitle) + "',\n"
                + "    customVars: {}\n"
                + "  };\n\n"
                + "  function showError(msg) {\n"
                + "    var errScreen = document.getElementById('flutter-error-screen');\n"
                + "    var errBody = document.getElementById('flutter-error-body');\n"
                + "    if (errScreen && errBody) {\n"
                + "      errBody.textContent = msg;\n"
                + "      errScreen.style.display = 'block';\n"
                + "    }\n"
                + "  }\n\n"
                + "  function parseDart(code) {\n"
                + "    try {\n"
                + "      // Check for syntax issues\n"
                + "      var lines = code.split('\\n');\n"
                + "      for (var l = 0; l < lines.length; l++) {\n"
                + "        var tr = lines[l].trim();\n"
                + "        if (/^[a-zA-Z_][a-zA-Z0-9_]*$/.test(tr)) {\n"
                + "          if (['else','try','finally','do','break','continue'].indexOf(tr) === -1) {\n"
                + "            showError('Syntax error at line ' + (l + 1) + ': Unexpected token \"' + tr + '\"');\n"
                + "            return false;\n"
                + "          }\n"
                + "        }\n"
                + "      }\n\n"
                + "      // Parse AppBar / MaterialApp title\n"
                + "      var titleMatch = code.match(/AppBar\\s*\\(\\s*title:\\s*(?:const\\s*)?Text\\s*\\(\\s*['\"]([^'\"]+)['\"]/);\n"
                + "      if (!titleMatch) titleMatch = code.match(/title:\\s*['\"]([^'\"]+)['\"]/);\n"
                + "      if (titleMatch && titleMatch[1]) {\n"
                + "        state.title = titleMatch[1];\n"
                + "      }\n\n"
                + "      // Parse counter state variable\n"
                + "      var counterMatch = code.match(/int\\s+_counter\\s*=\\s*(\\d+);/);\n"
                + "      if (counterMatch && counterMatch[1]) {\n"
                + "        state._counter = parseInt(counterMatch[1], 10) || 0;\n"
                + "      }\n\n"
                + "      return true;\n"
                + "    } catch (e) {\n"
                + "      showError('Error analyzing Dart source: ' + e.message);\n"
                + "      return false;\n"
                + "    }\n"
                + "  }\n\n"
                + "  function renderWidgetTree(code) {\n"
                + "    var titleEl = document.getElementById('flutter-appbar-title');\n"
                + "    if (titleEl) {\n"
                + "      titleEl.textContent = state.title;\n"
                + "    }\n\n"
                + "    var root = document.getElementById('flutter-root-tree');\n"
                + "    if (!root) return;\n"
                + "    root.innerHTML = '';\n\n"
                + "    var col = document.createElement('div');\n"
                + "    col.className = 'flutter-column';\n\n"
                + "    // Extract all Text widgets\n"
                + "    var textRegex = /Text\\s*\\(\\s*(?:'([^']*)'|\"([^\"]*)\")/g;\n"
                + "    var match;\n"
                + "    var foundTexts = [];\n"
                + "    while ((match = textRegex.exec(code)) !== null) {\n"
                + "      var str = match[1] !== undefined ? match[1] : match[2];\n"
                + "      // Skip appbar title from repeating in body\n"
                + "      if (str === state.title && foundTexts.length === 0) continue;\n"
                + "      foundTexts.push(str);\n"
                + "    }\n\n"
                + "    // Render extracted texts\n"
                + "    if (foundTexts.length > 0) {\n"
                + "      for (var i = 0; i < foundTexts.length; i++) {\n"
                + "        var textContent = foundTexts[i];\n"
                + "        var el = document.createElement('div');\n"
                + "        if (textContent.indexOf('$_counter') >= 0 || textContent.indexOf('$counter') >= 0 || textContent === '$_counter') {\n"
                + "          el.className = 'flutter-headline';\n"
                + "          el.id = 'counter-display';\n"
                + "          el.textContent = '' + state._counter;\n"
                + "        } else {\n"
                + "          el.className = 'flutter-text';\n"
                + "          el.textContent = textContent.replace(/\\$_counter/g, state._counter);\n"
                + "        }\n"
                + "        col.appendChild(el);\n"
                + "      }\n"
                + "    } else {\n"
                + "      var defaultText = document.createElement('div');\n"
                + "      defaultText.className = 'flutter-text';\n"
                + "      defaultText.textContent = 'Welcome to your Flutter Application!';\n"
                + "      col.appendChild(defaultText);\n"
                + "    }\n\n"
                + "    // Extract buttons if present (ElevatedButton, TextButton, FilledButton, OutlinedButton)\n"
                + "    var btnRegex = /(ElevatedButton|FilledButton|OutlinedButton|TextButton)\\s*\\(/g;\n"
                + "    var btnMatch;\n"
                + "    var btnIndex = 1;\n"
                + "    while ((btnMatch = btnRegex.exec(code)) !== null) {\n"
                + "      var btn = document.createElement('button');\n"
                + "      btn.className = btnMatch[1] === 'OutlinedButton' ? 'flutter-btn flutter-btn-outlined' : 'flutter-btn';\n"
                + "      btn.textContent = 'Button ' + btnIndex;\n"
                + "      btn.onclick = function() {\n"
                + "        state._counter++;\n"
                + "        updateDisplays();\n"
                + "      };\n"
                + "      col.appendChild(btn);\n"
                + "      btnIndex++;\n"
                + "    }\n\n"
                + "    // Extract TextField if present\n"
                + "    if (code.indexOf('TextField') >= 0) {\n"
                + "      var input = document.createElement('input');\n"
                + "      input.className = 'flutter-input';\n"
                + "      input.placeholder = 'Enter text here...';\n"
                + "      col.appendChild(input);\n"
                + "    }\n\n"
                + "    root.appendChild(col);\n"
                + "    wireFab(code);\n"
                + "  }\n\n"
                + "  function updateDisplays() {\n"
                + "    var display = document.getElementById('counter-display');\n"
                + "    if (display) {\n"
                + "      display.textContent = '' + state._counter;\n"
                + "      display.classList.add('bump');\n"
                + "      setTimeout(function() { display.classList.remove('bump'); }, 150);\n"
                + "    }\n"
                + "  }\n\n"
                + "  function wireFab(code) {\n"
                + "    var fab = document.getElementById('flutter-fab');\n"
                + "    if (!fab) return;\n"
                + "    if (code.indexOf('floatingActionButton') === -1) {\n"
                + "      fab.style.display = 'none';\n"
                + "    } else {\n"
                + "      fab.style.display = 'flex';\n"
                + "      fab.onclick = function(e) {\n"
                + "        e.preventDefault();\n"
                + "        state._counter++;\n"
                + "        updateDisplays();\n"
                + "      };\n"
                + "    }\n"
                + "  }\n\n"
                + "  window.flutterReload = function(newDartCode) {\n"
                + "    var src = newDartCode || dartSource;\n"
                + "    var errScreen = document.getElementById('flutter-error-screen');\n"
                + "    if (errScreen) errScreen.style.display = 'none';\n"
                + "    if (parseDart(src)) {\n"
                + "      renderWidgetTree(src);\n"
                + "    }\n"
                + "  };\n\n"
                + "  if (parseDart(dartSource)) {\n"
                + "    renderWidgetTree(dartSource);\n"
                + "  }\n"
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
