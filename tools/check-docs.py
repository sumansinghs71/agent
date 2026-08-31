#!/usr/bin/env python3
"""Documentation quality gate.

Three checks, all failing the build:

1. Every relative link resolves to a file that exists.
2. Every in-document anchor resolves to a real heading.
3. No canonical document contains build-session narration or assistant references.

Docs in this repository link to the code that backs each claim, so a broken link is the first
visible symptom of documentation drifting away from the implementation. Narration checks exist
because a reviewer-facing document should read as an engineering report, not as a transcript of how
it came to be written.
"""
import glob
import os
import re
import sys

LINK = re.compile(r'\[[^\]]*\]\(([^)\s]+)(?:\s+"[^"]*")?\)')
HEADING = re.compile(r'^(#{1,6})\s+(.*?)\s*$', re.M)

# Phrases that mark a document as a build transcript rather than a report.
NARRATION = [
    r'\bI (?:found|fixed|was wrong|initially|then|will|have not|recommend|replayed)\b',
    r'\bMy (?:hypothesis|test)\b',
    r'\bNow I\b', r'\bThen I\b',
    r'\bclaude\b', r'\banthropic\b',
    r'\bwe.ll allow but log\b',
]
NARRATION_RE = [re.compile(p, re.I) for p in NARRATION]

# Files where a literal branch/ref name legitimately appears as operational data.
NARRATION_EXEMPT_SUBSTRINGS = ['claude/analyze-repository-deep', 'claude/daily-coding-java-questions']


def slugify(heading: str) -> str:
    s = heading.strip().lower()
    s = re.sub(r'`|\*|_|\[|\]|\(|\)', '', s)
    s = re.sub(r'[^\w\s-]', '', s)
    return re.sub(r'\s+', '-', s).strip('-')


def anchors_of(path: str) -> set:
    try:
        text = open(path, encoding='utf-8').read()
    except OSError:
        return set()
    return {slugify(m.group(2)) for m in HEADING.finditer(text)}


def main() -> int:
    docs = ['README.md', 'SECURITY.md', 'CONTRIBUTING.md', 'METRICS.md']
    docs += sorted(glob.glob('docs/**/*.md', recursive=True))
    docs = [d for d in docs if os.path.exists(d)]

    broken_links, broken_anchors, narration = [], [], []

    for path in docs:
        base = os.path.dirname(path)
        text = open(path, encoding='utf-8').read()

        for m in LINK.finditer(text):
            target = m.group(1).strip()
            if target.startswith(('http://', 'https://', 'mailto:')):
                continue

            file_part, _, anchor = target.partition('#')

            if not file_part:                       # same-document anchor
                if anchor and slugify(anchor) not in anchors_of(path):
                    broken_anchors.append(f'{path} -> #{anchor}')
                continue

            # "File.java:42" - the line suffix is for humans; strip before resolving
            resolved = os.path.normpath(os.path.join(base, file_part.split(':')[0]))
            if not os.path.exists(resolved):
                broken_links.append(f'{path} -> {target}')
            elif anchor and resolved.endswith('.md'):
                if slugify(anchor) not in anchors_of(resolved):
                    broken_anchors.append(f'{path} -> {target}')

        for line_no, line in enumerate(text.splitlines(), 1):
            if any(x in line for x in NARRATION_EXEMPT_SUBSTRINGS):
                continue
            for rx in NARRATION_RE:
                if rx.search(line):
                    narration.append(f'{path}:{line_no}: {line.strip()[:90]}')
                    break

    failed = False
    for label, items in (('broken relative link', broken_links),
                         ('broken anchor', broken_anchors),
                         ('build-session narration', narration)):
        if items:
            failed = True
            print(f'{len(items)} {label}(s):')
            for i in items:
                print('  ', i)

    if not failed:
        print(f'{len(docs)} documents: links resolve, anchors resolve, no narration.')
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
