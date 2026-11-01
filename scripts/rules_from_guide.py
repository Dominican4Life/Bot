#!/usr/bin/env python3
"""KMK (r96): writes app/src/main/assets/sourcelab/rules.md, the Source Lab's rules file, from the fork guide.

The body is guide 08 "7. Rules for this app" and "8. Mistakes to avoid", copied as they are so the two never drift.
The YAML front matter (the machine rules the Lab checks) is hand-kept in RULES below: ids, severities, the fixed
check kinds (jssource/lab/LabTypes.kt LintCheck), the section each rule points at, and the fix / apply lines.

Run from the repo root:  python3 scripts/rules_from_guide.py
Bump VERSION whenever RULES or the guide sections change; the Lab only imports a file with a higher version.

Rules for the front matter (jssource/lab/RulesFile.kt reads a YAML subset): scalars, quoted strings ("…" with
\\-escapes, '…' literal with '' for a quote), inline maps { k: v } and inline lists [a, b] on one line. Regex
patterns go in single quotes so backslashes stay as written. No rule may match the owner's HIDDEN_TAGS filter
(`HIDDEN_TAGS`, `.indexOf(`, `.some(`): CONTRACT-LAB §7.3.
"""
import re
import sys
from pathlib import Path

VERSION = 11

RULES = r"""
  - id: og-attr-unquoted
    severity: warn
    check: regex-in-code
    section: "8.1"
    title: Quote attribute values with a colon in the selector
    fix: "In meta[property=og:image] the colon reads as a pseudo-class. Quote the value: meta[property='og:image']."
    snippet: "doc.attr(\"meta[property='og:image']\", \"content\")"
    apply: { find: "[property=og:image]", replace: "[property='og:image']" }
    params: { pattern: '\[(?:property|name|rel|itemprop)=[^''"\]\s]*:[^''"\]]*\]' }
  - id: text-on-element
    severity: error
    check: regex-in-code
    section: "8.1"
    title: text(css) is only on the page, not on an element
    fix: "On an element, text is a string. Find the child first: var c = await el.selectOne(css); c ? c.text : null."
    snippet: "async function subText(el, css) { var c = await el.selectOne(css); return c ? c.text : null; }"
    params: { pattern: '\b(?!doc\b)(?!page\b)(?!html\b)[A-Za-z_]\w*\.text\(\s*["''`]', unless: 'await\s+doc\.|JSON\.|res(ponse)?\.text\(' }
  - id: selector-generated-class
    severity: warn
    check: selector-pattern
    section: "8.1"
    title: A generated class name in a selector
    fix: "Classes like css-1x2y3z change on every build of the site. Pick a parent with a stable class, or an attribute."
    params: { pattern: '\.(css|sc)-[a-z0-9]{4,}' }
  - id: relative-url-returned
    severity: error
    check: result-field
    section: "8.2"
    title: A relative address was returned
    fix: "The app needs https://… for pages, covers and videos. Join with the base: path.startsWith('/') ? BASE + path : require('url').resolve(pageUrl, path)."
    params: { kind: relative-url, field: "coverUrl,imageUrl,videoUrl,webUrl,thumbnailUrl" }
  - id: image-field-server-path
    severity: warn
    check: result-field
    section: "8.2"
    title: An image address is a server path
    fix: "Skip values with // right after the host or a /home/…/wp-content/ path, and fall back to the other field."
    params: { kind: server-path, field: "coverUrl,imageUrl,thumbnailUrl" }
  - id: chapter-url-matches-saved
    severity: error
    check: compare-saved-chapter-urls
    section: "8.2"
    title: Chapter addresses must match what the app saved
    fix: "Print whole numbers as 1.0 like the APK: Number.isInteger(n) ? n + \".0\" : String(n)"
    apply: { find: "String(n)", replace: '(Number.isInteger(n) ? n + ".0" : String(n))' }
    applies: [manga, apk-port]
  - id: referer-on-images
    severity: warn
    check: header-present
    section: "8.2"
    title: Covers or pages need a Referer
    fix: "Set headers: { Referer: BASE + '/' } on the extension object; the app sends them for covers and pages."
    snippet: "headers: { Referer: BASE + \"/\" },"
    params: { header: Referer, on: images }
    applies: [manga]
  - id: status-default-filled
    severity: warn
    check: result-field
    section: "8.3"
    title: A status filled in for every entry
    fix: "Leave status out when the site doesn't say; the app shows it as a badge on every cover."
    params: { kind: same-status }
  - id: date-format-known
    severity: warn
    check: result-field
    section: "8.3"
    title: dateUploaded in a format the app can't read
    fix: "Give an ISO date (2026-10-30T12:00:00Z), a date (2026-10-30) or epoch seconds / milliseconds; a human date reads as 0."
    params: { kind: date-zero }
  - id: cover-in-details
    severity: info
    check: text
    section: "8.3"
    title: Only return coverUrl from getDetails when the list has none
    fix: "A coverUrl from getDetails replaces the list's picture after the title is opened."
    applies: [manga]
  - id: json-setting-placeholder
    severity: info
    check: text
    section: "8.4"
    title: A setting's default doesn't fill a JSON placeholder
    fix: "Write the default into the address and offer the other choices as catalogs, or write the source in JS."
    applies: [json]
  - id: cloudflare-same-page-twice
    severity: info
    check: text
    section: "8.5"
    title: Don't ask a Cloudflare site for the same page twice
    fix: "Read a page once and keep the parsed document; the second request is the one that gets the bot check."
    applies: [cloudflare]
  - id: referer-on-videos
    severity: warn
    check: header-present
    section: "8.6"
    title: Every video needs a Referer
    fix: "Give each video headers: { Referer: embedPageUrl } (or set headers on the extension object); hosters answer 403 without it."
    snippet: "{ url: stream, quality: \"1080p\", server: \"StreamWish\", headers: { Referer: embedUrl } }"
    params: { header: Referer, on: videos }
    applies: [anime, videos]
  - id: render-no-useragent-cf
    severity: warn
    check: render-option
    section: "8.6"
    title: host.render with its own userAgent on a Cloudflare site
    fix: "Leave userAgent out of host.render options: the hidden browser must look like the one that passed the bot check."
    params: { option: userAgent }
  - id: rate-limit-touchy
    severity: warn
    check: response-status
    section: "8.7"
    title: The site answered 429 and the source has no rateLimitMs
    fix: "Add rateLimitMs: 1000 (or more) to the extension object; the runtime spaces the requests."
    snippet: "rateLimitMs: 1500,"
    params: { status: 429, unless: 'rateLimitMs' }
  - id: debug-log-reaches-logs
    severity: info
    check: text
    section: "8.8"
    title: console.log lines reach the Logs screen
    fix: "Take debug prints out before saving; every console line goes to Logs under js:<name>."
  - id: tabs-not-needed
    severity: info
    check: text
    section: "8.8"
    title: Tabs a source doesn't need
    fix: "Only give getCatalogs tabs the site itself has as lists; Popular and Latest already exist."
  - id: servers-not-skipped
    severity: info
    check: text
    section: "8.8"
    title: List each server the site offers
    fix: "Two servers that play the same video are both listed; name videos by what the group heading doesn't say."
    applies: [anime]
""".strip("\n")


def main() -> int:
    root = Path(__file__).resolve().parent.parent
    guide = root / "docs/fork-guide/08-writing-js-json-sources.md"
    out = root / "app/src/main/assets/sourcelab/rules.md"
    lines = guide.read_text(encoding="utf-8").splitlines()
    start = next(i for i, l in enumerate(lines) if re.match(r"^## 7\. ", l))
    end = next((i for i, l in enumerate(lines) if i > start and re.match(r"^## (9|[1-9]\d)\. ", l)), len(lines))
    body = lines[start:end]
    # Anchors in the guide point at headings of the same file; they stay readable as text in the Lab's renderer
    text = "\n".join(body).rstrip() + "\n"
    front = "\n".join(
        [
            "---",
            f"version: {VERSION}",
            "# Komikku Source Lab rules file. Body: fork guide 08 §7–§8 (scripts/rules_from_guide.py).",
            "# Machine rules below; `check` is one of regex-in-code, selector-pattern, result-field,",
            "# compare-saved-chapter-urls, header-present, response-status, render-option, or text (read only).",
            "rules:",
            RULES,
            "---",
            "",
        ]
    )
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(front + text, encoding="utf-8")
    ids = re.findall(r"^\s+- id: (\S+)", RULES, re.M)
    print(f"wrote {out.relative_to(root)}: v{VERSION}, {len(ids)} rules, {len(body)} body lines")
    forbidden = ("HIDDEN_TAGS", ".indexOf(", ".some(")
    for word in forbidden:
        if word in RULES:
            print(f"refusing: a rule mentions {word} (CONTRACT-LAB §7.3)")
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
