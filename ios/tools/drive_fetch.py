#!/usr/bin/env python3
"""Downloads a shared Google Drive file by id (also past the «can't scan for viruses» page of large files).

  drive_fetch.py <file id> <out path>
"""
import html
import re
import sys
import urllib.parse
import urllib.request


def fetch(file_id, out):
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor())
    url = 'https://drive.google.com/uc?export=download&id=' + urllib.parse.quote(file_id)
    with opener.open(url, timeout=120) as r:
        kind = r.headers.get('Content-Type', '')
        if 'text/html' not in kind:
            return save(r, out)
        page = r.read().decode('utf-8', 'replace')
    form = re.search(r'<form[^>]+id="download-form"[^>]+action="([^"]+)"', page)
    if not form:
        raise SystemExit(f'{file_id}: no download form (not shared publicly?)')
    fields = dict(re.findall(r'<input type="hidden" name="([^"]+)" value="([^"]*)"', page))
    target = html.unescape(form.group(1)) + '?' + urllib.parse.urlencode(fields)
    with opener.open(target, timeout=600) as r:
        if 'text/html' in r.headers.get('Content-Type', ''):
            raise SystemExit(f'{file_id}: Drive returned a page instead of the file')
        return save(r, out)


def save(response, out):
    total = 0
    with open(out, 'wb') as f:
        while True:
            chunk = response.read(1 << 22)
            if not chunk:
                break
            f.write(chunk)
            total += len(chunk)
    print(f'{out}: {total / 1e6:.1f} MB')


if __name__ == '__main__':
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    fetch(sys.argv[1], sys.argv[2])
