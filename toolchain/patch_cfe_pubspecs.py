#!/usr/bin/env python3
"""
Sumer Studio Dart toolchain vendoring script.

Takes a sparse checkout of dart-lang/sdk (pkg/front_end, pkg/kernel,
pkg/_fe_analyzer_shared, pkg/build_integration, pkg/vm) and patches the
pubspecs so they resolve standalone (outside the SDK's pub workspace).

Usage: python3 patch_cfe_pubspecs.py <vendored_dir>
  <vendored_dir> must contain: front_end kernel _fe_analyzer_shared
                              build_integration vm
"""

import sys
import os

REQUIRED = ["front_end", "kernel", "_fe_analyzer_shared", "build_integration", "vm"]

PUB_VERSIONS = {
    "package_config": "^2.1.0",
    "yaml": "^3.1.0",
    "args": "^2.4.0",
    "collection": "^1.18.0",
    "crypto": "^3.0.0",
    "path": "^1.9.0",
    "record_use": "^1.0.0",
    "meta": "^1.16.0",
}


def patch(path, replacements):
    if not os.path.exists(path):
        print(f"  !! missing {path}")
        sys.exit(1)
    s = open(path).read()
    for old, new in replacements:
        if old not in s:
            # Idempotency: already patched
            if new in s:
                continue
            print(f"  !! pattern not found in {path}: {old[:60]!r}")
            sys.exit(1)
        s = s.replace(old, new)
    open(path, "w").write(s)
    print(f"  patched {path}")


def strip_workspace(s):
    # Remove 'resolution: workspace' lines and 'workspace:' blocks.
    s = s.replace("resolution: workspace\n", "")
    lines = s.splitlines(keepends=True)
    out, in_block = [], False
    for line in lines:
        if line.startswith("workspace:"):
            in_block = True
            continue
        if in_block:
            if line.startswith(("  -", " ", "\t")):
                continue
            in_block = False
        out.append(line)
    return "".join(out)


def main():
    root = sys.argv[1]
    for pkg in REQUIRED:
        if not os.path.isdir(os.path.join(root, pkg)):
            print(f"missing vendored package: {pkg}")
            sys.exit(1)

    # _fe_analyzer_shared: strip workspace only.
    p = os.path.join(root, "_fe_analyzer_shared", "pubspec.yaml")
    s = strip_workspace(open(p).read())
    open(p, "w").write(s)
    print("  patched", p)

    # kernel: path dep on _fe_analyzer_shared.
    p = os.path.join(root, "kernel", "pubspec.yaml")
    s = strip_workspace(open(p).read())
    s = s.replace(
        "dependencies:\n  _fe_analyzer_shared: any",
        "dependencies:\n  _fe_analyzer_shared:\n    path: ../_fe_analyzer_shared",
    )
    open(p, "w").write(s)
    print("  patched", p)

    # front_end: path deps + pub versions.
    p = os.path.join(root, "front_end", "pubspec.yaml")
    s = strip_workspace(open(p).read())
    s = s.replace(
        """dependencies:
  _fe_analyzer_shared: any
  kernel: any
  package_config: any
  yaml: any""",
        """dependencies:
  _fe_analyzer_shared:
    path: ../_fe_analyzer_shared
  kernel:
    path: ../kernel
  package_config: {package_config}
  yaml: {yaml}""".format(**PUB_VERSIONS),
    )
    open(p, "w").write(s)
    print("  patched", p)

    # build_integration: path dep on front_end.
    p = os.path.join(root, "build_integration", "pubspec.yaml")
    s = strip_workspace(open(p).read())
    s = s.replace("  front_end: any", "  front_end:\n    path: ../front_end")
    open(p, "w").write(s)
    print("  patched", p)

    # vm: path deps + pub versions.
    p = os.path.join(root, "vm", "pubspec.yaml")
    s = strip_workspace(open(p).read())
    s = s.replace(
        """dependencies:
  args: any
  build_integration: any
  collection: any
  crypto: any
  front_end: any
  kernel: any
  package_config: any
  path: any
  record_use: any
  yaml: any""",
        """dependencies:
  args: {args}
  build_integration:
    path: ../build_integration
  collection: {collection}
  crypto: {crypto}
  front_end:
    path: ../front_end
  kernel:
    path: ../kernel
  package_config: {package_config}
  path: {path}
  record_use: {record_use}
  yaml: {yaml}""".format(**PUB_VERSIONS),
    )
    open(p, "w").write(s)
    print("  patched", p)

    print("All vendored pubspecs patched.")


if __name__ == "__main__":
    main()
