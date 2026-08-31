#!/usr/bin/env python3
"""Fail if a relative markdown link points at something that does not exist.

Docs that link to code are the main defence against this repository's claims drifting away from
its implementation. A broken link is the first symptom of that drift, so it fails the build.
"""
import glob, os, re, sys

LINK = re.compile(r'\[[^\]]+\]\(([^)#]+?)(?:#[^)]*)?\)')
broken = []

for path in ['README.md', 'SECURITY.md', 'CONTRIBUTING.md'] + glob.glob('docs/**/*.md', recursive=True):
    if not os.path.exists(path):
        continue
    base = os.path.dirname(path)
    for m in LINK.finditer(open(path, encoding='utf-8').read()):
        target = m.group(1).strip()
        if target.startswith(('http://', 'https://', 'mailto:')):
            continue
        # "file.java:42" - the line suffix is for humans, strip it before resolving
        resolved = os.path.normpath(os.path.join(base, target.split(':')[0]))
        if not os.path.exists(resolved):
            broken.append(f"{path} -> {target}")

if broken:
    print(f"{len(broken)} broken relative link(s):")
    for b in broken:
        print("  ", b)
    sys.exit(1)
print("All relative documentation links resolve.")
