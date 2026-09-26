import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import prepare_local_dependencies as subject


class DependencyPreparationTest(unittest.TestCase):
    def test_new_secrets_and_replay_preserves_files(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); (root/'config').mkdir()
            (root/'config/compose.local.yml').write_text('services: {}')
            target = root/'.runtime/deps'
            subject.prepare(target, root)
            before = {p.name: p.read_bytes() for p in target.iterdir()}
            with self.assertRaises(FileExistsError): subject.prepare(target, root)
            self.assertEqual(before, {p.name: p.read_bytes() for p in target.iterdir()})
            sql = (target/'app-user.sql').read_text()
            self.assertEqual(5, sql.count('GRANT SELECT, INSERT, UPDATE, DELETE'))
            self.assertNotIn('ALL PRIVILEGES', sql)
            self.assertNotIn('CREATE DATABASE', sql)

    def test_escape_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for dest in (root/'.runtime', root/'outside', root/'.runtime/../outside'):
                with self.assertRaises(ValueError): subject.prepare(dest, root)

    def test_failure_keeps_partial_directory_and_refuses_retry(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); target = root/'.runtime/deps'
            with patch.object(subject.shutil, 'copyfile', side_effect=OSError('injected')):
                with self.assertRaises(OSError): subject.prepare(target, root)
            self.assertTrue(target.exists())
            with self.assertRaises(FileExistsError): subject.prepare(target, root)


if __name__ == '__main__': unittest.main()
