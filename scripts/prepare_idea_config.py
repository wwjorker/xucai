"""Create a git-ignored Spring properties file from local portable settings.

Never prints values. Does not start services, change credentials or call providers.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / '.runtime' / 'idea-private.properties'


def properties(settings):
    if not isinstance(settings, dict) or not settings:
        raise ValueError('Expected a nonempty settings object')
    lines = ['# PRIVATE: generated locally; never commit or share.']
    for key, value in settings.items():
        if not re.fullmatch(r'XUCAI_[A-Z0-9_]+', key):
            raise ValueError('Unexpected configuration key; no output written')
        if not isinstance(value, (str, int, bool)):
            raise ValueError('Only scalar settings are accepted')
        value = str(value).lower() if isinstance(value, bool) else str(value)
        # ASCII properties work across Java/IDE encodings, including Windows paths.
        escaped = ''
        for char in value:
            if char == '\\': escaped += '\\\\'
            elif char == '\n': escaped += '\\n'
            elif char == '\r': escaped += '\\r'
            elif char == '\t': escaped += '\\t'
            elif char in ' =:#!': escaped += '\\' + char
            elif ord(char) > 127:
                raw = char.encode('utf-16-be')
                escaped += ''.join('\\u' + raw[i:i+2].hex() for i in range(0, len(raw), 2))
            else: escaped += char
        lines.append(key + '=' + escaped)
    return '\n'.join(lines) + '\n'


def prepare(source, overwrite=False):
    # Fixed output under the ignored runtime directory; never accept arbitrary destinations.
    relative = OUTPUT.relative_to(ROOT).as_posix()
    if (ROOT / '.git').exists():
        ignored = subprocess.run(['git', '-C', str(ROOT), 'check-ignore', '-q', relative]).returncode
        tracked = subprocess.run(['git', '-C', str(ROOT), 'ls-files', '--error-unmatch', relative],
                                 stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode
        safe = ignored == 0 and tracked != 0
    else:
        # A downloaded source package need not have a Git repository yet.
        rules = (ROOT / '.gitignore').read_text(encoding='utf-8-sig').splitlines()
        safe = '.runtime/' in rules and not any(x.startswith('!') for x in rules)
    if not safe:
        raise RuntimeError('Private output must be untracked and ignored by Git')
    if OUTPUT.exists() and not overwrite:
        raise RuntimeError('Private file already exists; use --overwrite to refresh deliberately')
    text = properties(json.loads(source.read_text(encoding='utf-8-sig')))
    OUTPUT.parent.mkdir(exist_ok=True)
    temporary = OUTPUT.with_suffix('.properties.tmp')
    temporary.write_text(text, encoding='ascii')
    temporary.replace(OUTPUT)
    return OUTPUT


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--overwrite', action='store_true')
    args = parser.parse_args()
    try:
        output = prepare(args.source, args.overwrite)
    except Exception:
        # JSON parsing exceptions can contain source fragments; never echo them.
        raise SystemExit('Configuration not written. Check source JSON, Git ignore and overwrite option.')
    print('Created private configuration: ' + str(output))


if __name__ == '__main__':
    main()
