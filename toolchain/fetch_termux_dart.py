#!/usr/bin/env python3
"""
Sumer Studio — fetch the Termux dart-sdk build for Android (aarch64).

Used by:
  * .github/workflows/dart-toolchain.yml  (full dart-sdk data for the toolchain zip)
  * .github/workflows/android.yml         (the VM binary as libsumerdart.so jniLib)

Behaviour:
  1. Reads the Termux aarch64 package index.
  2. Finds the dart package whose version matches --version-prefix
     (e.g. "3.10.4" matches "3.10.4" and "3.10.4-1").
     If no match: prints all available dart versions and exits 2.
  3. Downloads + extracts the deb.
  4. Copies the dart-sdk tree to <out>/dart-sdk.
  5. Resolves the deb's runtime shared-library dependencies and extracts
     their .so files to <out>/deps-libs (so they can be shipped next to the
     VM binary and found via LD_LIBRARY_PATH).

Usage:
  python3 fetch_termux_dart.py --version-prefix 3.10.4 --out-dir /tmp/out \
      [--dartvm-out /path/libsumerdart.so]
"""

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.request

TERMUX_ARCH = "aarch64"
PACKAGES_URL = (
    "https://packages.termux.dev/apt/termux-main/"
    f"dists/stable/main/binary-{TERMUX_ARCH}/Packages"
)
BASE = "https://packages.termux.dev/apt/termux-main/"
# Libraries provided by Android itself (bionic) — never bundle these.
BIONIC = {"libc.so", "libm.so", "libdl.so", "librt.so", "libpthread.so",
          "libresolv.so", "libstdc++.so", "libz.so", "liblog.so"}


def http_get(url, dest):
    print(f"  GET {url}")
    req = urllib.request.Request(url, headers={"User-Agent": "sumer-toolchain/1.0"})
    with urllib.request.urlopen(req, timeout=120) as r, open(dest, "wb") as f:
        shutil.copyfileobj(r, f)


def read_index(cache_dir):
    idx = os.path.join(cache_dir, "Packages")
    if not os.path.exists(idx):
        http_get(PACKAGES_URL, idx)
    packages = {}
    current = {}
    with open(idx, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line:
                if current.get("Package"):
                    packages.setdefault(current["Package"], current)
                current = {}
                continue
            if ": " in line:
                k, v = line.split(": ", 1)
                current[k] = v
    if current.get("Package"):
        packages.setdefault(current["Package"], current)
    return packages


def pick_dart(packages, version_prefix, latest=False):
    entry = packages.get("dart")
    if not entry:
        return None
    if latest:
        return entry
    version = entry.get("Version", "")
    if version == version_prefix or version.startswith(version_prefix + "-") \
            or version.startswith(version_prefix + "+"):
        return entry
    return None


def normalize_dep(dep):
    dep = dep.strip()
    dep = re.split(r"[\s(]", dep)[0]
    return dep


def download_and_extract(packages, name, workdir):
    entry = packages.get(name)
    if not entry:
        print(f"  !! dependency package not found in repo: {name}")
        return None
    filename = entry["Filename"]
    deb = os.path.join(workdir, f"{name}.deb")
    http_get(BASE + filename, deb)
    out = os.path.join(workdir, f"x_{name}")
    os.makedirs(out, exist_ok=True)
    subprocess.run(["dpkg-deb", "-x", deb, out], check=True)
    os.remove(deb)
    return out


def find_dart_sdk_root(extract_dir):
    hits = []
    for root, dirs, files in os.walk(extract_dir):
        if "dart" in files and os.path.basename(root) == "bin":
            hits.append(os.path.dirname(root))
    if not hits:
        raise SystemExit("  !! could not locate dart-sdk root (bin/dart) inside deb")
    hits.sort(key=len)  # shortest path = sdk root
    return hits[0]


def find_shared_libs(extract_dir):
    libs = []
    for root, dirs, files in os.walk(extract_dir):
        for f in files:
            if f.endswith(".so"):
                libs.append(os.path.join(root, f))
    return libs


def needed_libs(elf_path):
    try:
        out = subprocess.run(["readelf", "-d", elf_path],
                             capture_output=True, text=True, timeout=30)
    except Exception as e:
        print(f"  !! readelf failed for {elf_path}: {e}")
        return []
    libs = []
    for m in re.finditer(r"Shared library: \[([^\]]+)\]", out.stdout):
        libs.append(m.group(1))
    return libs


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--version-prefix", required=True)
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--dartvm-out", default=None,
                    help="also copy bin/dart to this path (e.g. libsumerdart.so)")
    ap.add_argument("--latest", action="store_true",
                    help="use the current Termux dart version instead of matching --version-prefix")
    args = ap.parse_args()

    workdir = "/tmp/termux_dart_work"
    shutil.rmtree(workdir, ignore_errors=True)
    os.makedirs(workdir, exist_ok=True)
    os.makedirs(args.out_dir, exist_ok=True)

    print("Reading Termux package index...")
    packages = read_index(workdir)
    entry = pick_dart(packages, args.version_prefix, latest=args.latest)
    if entry is None:
        available = sorted({p.get("Version", "?") for n, p in packages.items() if n == "dart"})
        print(json.dumps({
            "error": "no matching termux dart version",
            "wanted": args.version_prefix,
            "available": available,
        }, indent=2))
        sys.exit(2)
    version = entry["Version"]
    print(f"Termux dart version: {version}")

    extract = download_and_extract(packages, "dart", workdir)
    sdk_root = find_dart_sdk_root(extract)

    out_sdk = os.path.join(args.out_dir, "dart-sdk")
    if os.path.exists(out_sdk):
        shutil.rmtree(out_sdk)
    shutil.copytree(sdk_root, out_sdk)
    print(f"  dart-sdk copied to {out_sdk}")

    if args.dartvm_out:
        vm_src = os.path.join(out_sdk, "bin", "dart")
        os.makedirs(os.path.dirname(args.dartvm_out), exist_ok=True)
        shutil.copyfile(vm_src, args.dartvm_out)
        os.chmod(args.dartvm_out, 0o755)
        print(f"  VM binary copied to {args.dartvm_out}")

    # Shared library deps (one level): from Depends field + libs shipped in the deb.
    deps_out = os.path.join(args.out_dir, "deps-libs")
    os.makedirs(deps_out, exist_ok=True)
    wanted = set()
    for dep in entry.get("Depends", "").split(","):
        dep = normalize_dep(dep)
        if dep:
            wanted.add(dep)
    # also anything the deb itself ships
    for lib in find_shared_libs(extract):
        if os.path.dirname(lib) != os.path.join(sdk_root, "bin"):
            shutil.copyfile(lib, os.path.join(deps_out, os.path.basename(lib)))
            print(f"  bundled lib from deb: {os.path.basename(lib)}")

    for name in sorted(wanted):
        # skip packages that are pure data or already handled
        if name in ("dart",):
            continue
        dep_dir = download_and_extract(packages, name, workdir)
        if not dep_dir:
            continue
        for lib in find_shared_libs(dep_dir):
            base = os.path.basename(lib)
            if base in BIONIC:
                continue
            shutil.copyfile(lib, os.path.join(deps_out, base))
            print(f"  bundled dependency lib: {base} (from {name})")

    meta = {
        "termux_dart_version": version,
        "wanted_prefix": args.version_prefix,
    }
    with open(os.path.join(args.out_dir, "termux_dart.json"), "w") as f:
        json.dump(meta, f, indent=2)
    print("Done.")


if __name__ == "__main__":
    main()
