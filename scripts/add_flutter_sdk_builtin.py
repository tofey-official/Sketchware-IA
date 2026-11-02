import zipfile
import io
import os

print("Processing Flutter SDK into built-in libs...")

# 1. Prepare classes.jar from official flutter.jar in SDK zip
sdk_zip_path = r"c:\Users\tareq\Downloads\Sketchware-IA\flutter_windows_3.47.9-stable.zip"
zf_sdk = zipfile.ZipFile(sdk_zip_path)
jar_bytes = zf_sdk.read("flutter/bin/cache/artifacts/engine/android-arm64-release/flutter.jar")
jar = zipfile.ZipFile(io.BytesIO(jar_bytes))

classes_buf = io.BytesIO()
classes_jar = zipfile.ZipFile(classes_buf, "w", zipfile.ZIP_DEFLATED)
for n in jar.namelist():
    if n.endswith(".class") and n.startswith("io/flutter/"):
        classes_jar.writestr(n, jar.read(n))
classes_jar.close()
classes_data = classes_buf.getvalue()

# 2. Native libflutter.so from verified release apk
apk = zipfile.ZipFile("tortilla_temp.apk")
libflutter_so = apk.read("lib/arm64-v8a/libflutter.so")

# 3. Dex
dex_data = open("flutter_temp.dex", "rb").read()

dexs_zip_path = os.path.join("app", "src", "main", "assets", "libs", "dexs.zip")
libs_zip_path = os.path.join("app", "src", "main", "assets", "libs", "libs.zip")

# Update dexs.zip
dex_entry_name = "flutter-embedding-3.47.9.dex"
with zipfile.ZipFile(dexs_zip_path, "a", zipfile.ZIP_DEFLATED) as z_dex:
    if dex_entry_name not in z_dex.namelist():
        z_dex.writestr(dex_entry_name, dex_data)
        print("Added", dex_entry_name, "to dexs.zip")
    else:
        print(dex_entry_name, "already in dexs.zip")

# Update libs.zip
prefix = "flutter-embedding-3.47.9/"
with zipfile.ZipFile(libs_zip_path, "a", zipfile.ZIP_DEFLATED) as z_libs:
    existing = set(z_libs.namelist())
    files_to_add = {
        prefix + "AndroidManifest.xml": b'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="io.flutter.embedding" />\n',
        prefix + "classes.jar": classes_data,
        prefix + "jni/arm64-v8a/libflutter.so": libflutter_so,
        prefix + "proguard.txt": b"-keep class io.flutter.** { *; }\n",
        prefix + "R.txt": b"",
        prefix + "public.txt": b"",
        prefix + "res/values/values.xml": b'<?xml version="1.0" encoding="utf-8"?>\n<resources></resources>\n'
    }
    for path, content in files_to_add.items():
        if path not in existing:
            z_libs.writestr(path, content)
            print("Added", path, "to libs.zip")
        else:
            print(path, "already exists in libs.zip")

print("Flutter SDK successfully added to built-in libs!")
