"""Prepare NEW private Docker Compose files; no Docker/network/database actions.

Default prints a plan. --create exclusively creates a directory under .runtime.
Never recreate a directory to change a live container's passwords.
"""
import argparse
import json
from pathlib import Path
import secrets
import shutil

ROOT = Path(__file__).resolve().parents[1]


def prepare(destination, root=ROOT):
    destination = Path(destination).resolve()
    runtime = (root / '.runtime').resolve()
    if not destination.is_relative_to(runtime) or destination == runtime:
        raise ValueError('Destination must be a new child of repository .runtime')
    destination.mkdir(parents=True, exist_ok=False, mode=0o700)
    # Failure keeps this directory for inspection; never overwrite or auto-delete.
    passwords = {name: secrets.token_hex(24) for name in ('root', 'app', 'redis')}
    sql = "CREATE USER 'xucai_app'@'%' IDENTIFIED BY '" + passwords['app'] + "';\n"
    for suffix in ('auth', 'user', 'catalog', 'trade', 'ai'):
        # MySQL database-level GRANT treats underscore as a wildcard, even quoted.
        sql += f"GRANT SELECT, INSERT, UPDATE, DELETE ON `xucai\\_{suffix}`.* TO 'xucai_app'@'%';\n"
    private = {
        'mysql-root': passwords['root'] + '\n',
        'app-user.sql': sql,
        'redis.conf': 'bind 0.0.0.0\nport 6379\nappendonly yes\nrequirepass ' + passwords['redis'] + '\n',
        'credentials.json': json.dumps({'mysqlRootPassword': passwords['root'],
            'appPassword': passwords['app'], 'redisPassword': passwords['redis']}, indent=2),
        '.env': 'XUCAI_MYSQL_PORT=13306\nXUCAI_NACOS_PORT=18848\nXUCAI_NACOS_GRPC_PORT=19848\nXUCAI_REDIS_PORT=16379\n',
    }
    for name, content in private.items():
        path = destination / name
        with path.open('x', encoding='utf-8', newline='\n') as f:
            f.write(content)
        # Container service users must read the three bind-mounted files.
        # The containing host directory remains private (0700).
        path.chmod(0o644 if name in ('mysql-root', 'app-user.sql', 'redis.conf') else 0o600)
    shutil.copyfile(root / 'config/compose.local.yml', destination / 'compose.yml')
    return {'prepared': True, 'directory': str(destination), 'containersStarted': False,
            'databaseSchemasCreated': False, 'secretsPrinted': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, default=ROOT / '.runtime/local-dependencies')
    parser.add_argument('--create', action='store_true')
    args = parser.parse_args()
    if not args.create:
        print(json.dumps({'action': 'PLAN_ONLY', 'services': ['mysql', 'nacos', 'redis-stack'],
                          'bind': '127.0.0.1', 'directory': str(args.directory)}, indent=2))
        return
    print(json.dumps(prepare(args.directory), indent=2))


if __name__ == '__main__':
    main()
