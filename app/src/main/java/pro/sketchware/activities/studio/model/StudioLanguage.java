package pro.sketchware.activities.studio.model;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import pro.sketchware.R;

/**
 * Defines supported programming languages for Android Studio projects.
 * Designed to be modular and easily extensible for future language additions (C++, Flutter, Rust, etc.).
 */
public enum StudioLanguage {
    JAVA(
            "java",
            "Java",
            "Classic Android development with Java",
            R.drawable.ic_mtrl_java,
            "MainActivity.java",
            true,
            false
    ),
    KOTLIN(
            "kotlin",
            "Kotlin",
            "Modern Android development with Kotlin",
            R.drawable.ic_mtrl_kotlin,
            "MainActivity.kt",
            false,
            true
    ),
    BOTH(
            "both",
            "Java & Kotlin",
            "Support both Java and Kotlin in the project",
            R.drawable.ic_mtrl_code,
            "MainActivity.java / AppUtils.kt",
            true,
            true
    ),
    FLUTTER(
            "flutter",
            "Flutter (Dart)",
            "Cross-platform app development with Flutter & Dart",
            R.drawable.ic_mtrl_flutter,
            "lib/main.dart",
            false,
            false,
            true
    );

    private final String id;
    private final String displayName;
    private final String description;
    @DrawableRes
    private final int iconRes;
    private final String mainFileName;
    private final boolean supportsJava;
    private final boolean supportsKotlin;
    private final boolean isFlutter;

    StudioLanguage(String id, String displayName, String description, @DrawableRes int iconRes,
                   String mainFileName, boolean supportsJava, boolean supportsKotlin) {
        this(id, displayName, description, iconRes, mainFileName, supportsJava, supportsKotlin, false);
    }

    StudioLanguage(String id, String displayName, String description, @DrawableRes int iconRes,
                   String mainFileName, boolean supportsJava, boolean supportsKotlin, boolean isFlutter) {
        this.id = id;
        this.displayName = displayName;
        this.description = description;
        this.iconRes = iconRes;
        this.mainFileName = mainFileName;
        this.supportsJava = supportsJava;
        this.supportsKotlin = supportsKotlin;
        this.isFlutter = isFlutter;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    @DrawableRes
    public int getIconRes() {
        return iconRes;
    }

    public String getMainFileName() {
        return mainFileName;
    }

    public boolean supportsJava() {
        return supportsJava;
    }

    public boolean supportsKotlin() {
        return supportsKotlin;
    }

    public boolean isFlutter() {
        return isFlutter;
    }

    @NonNull
    public static StudioLanguage fromId(@Nullable String id) {
        if (id != null) {
            for (StudioLanguage lang : values()) {
                if (lang.id.equalsIgnoreCase(id) || lang.name().equalsIgnoreCase(id)) {
                    return lang;
                }
            }
        }
        return JAVA;
    }

    @NonNull
    public static List<StudioLanguage> getAvailableLanguages() {
        List<StudioLanguage> list = new ArrayList<>();
        Collections.addAll(list, values());
        return list;
    }
}
