"""Generate a private local demo RSA key; never reuse a shared key or overwrite files."""
import argparse
import json
import os
from pathlib import Path
import secrets
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def generate(java_home, directory):
    tool = Path(java_home)/'bin'/('keytool.exe' if os.name == 'nt' else 'keytool')
    if not tool.is_file(): raise ValueError('JDK keytool not found')
    directory = Path(directory).resolve()
    if not directory.is_relative_to((ROOT/'.runtime').resolve()):
        raise ValueError('Private keys must stay inside the ignored repository .runtime directory')
    directory.mkdir(parents=True, exist_ok=False)
    key = directory/'jwt.jks'; password = secrets.token_urlsafe(32)
    env = dict(os.environ, XUCAI_KEYTOOL_PASSWORD=password)
    command = [str(tool), '-genkeypair', '-alias', 'xucai-local', '-keyalg', 'RSA', '-keysize', '2048',
               '-validity', '3650', '-dname', 'CN=Xucai Local Demo', '-storetype', 'JKS', '-keystore', str(key),
               '-storepass:env', 'XUCAI_KEYTOOL_PASSWORD', '-keypass:env', 'XUCAI_KEYTOOL_PASSWORD', '-noprompt']
    result = subprocess.run(command, env=env, capture_output=True, timeout=60)
    if result.returncode or not key.is_file():
        raise RuntimeError('Key generation failed; partial directory retained, never retry over it')
    settings = {'XUCAI_JWT_ALIAS': 'xucai-local', 'XUCAI_JWT_LOCATION': key.as_uri(),
                'XUCAI_JWT_PASSWORD': password}
    with (directory/'jwt-env.json').open('x', encoding='utf-8') as f:
        json.dump(settings, f, indent=2)
    return {'directory': str(directory), 'keyCreated': True, 'environmentFile': str(directory/'jwt-env.json')}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--java-home', required=True)
    p.add_argument('--directory', type=Path, default=ROOT/'.runtime/portable-jwt')
    a=p.parse_args(); print(json.dumps(generate(a.java_home,a.directory)))


if __name__ == '__main__': main()
