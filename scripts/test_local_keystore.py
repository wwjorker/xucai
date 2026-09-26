import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import generate_local_keystore as g


class KeystoreTest(unittest.TestCase):
    def test_rejects_existing_directory_without_running_keytool(self):
        with tempfile.TemporaryDirectory() as d, patch.object(g,'ROOT',Path(d)), patch.object(g.subprocess,'run') as run:
            tool=Path(d)/'jdk/bin'/('keytool.exe' if os.name=='nt' else 'keytool');tool.parent.mkdir(parents=True);tool.touch()
            target=Path(d)/'.runtime/keys';target.mkdir(parents=True)
            with self.assertRaises(FileExistsError):g.generate(Path(d)/'jdk',target)
            run.assert_not_called()

    def test_rejects_public_output_location(self):
        with tempfile.TemporaryDirectory() as d, patch.object(g,'ROOT',Path(d)):
            tool=Path(d)/'jdk/bin'/('keytool.exe' if os.name=='nt' else 'keytool');tool.parent.mkdir(parents=True);tool.touch()
            with self.assertRaises(ValueError):g.generate(Path(d)/'jdk',Path(d)/'public')

    def test_password_is_not_in_process_arguments_or_public_result(self):
        with tempfile.TemporaryDirectory() as d, patch.object(g,'ROOT',Path(d)):
            tool=Path(d)/'jdk/bin'/('keytool.exe' if os.name=='nt' else 'keytool');tool.parent.mkdir(parents=True);tool.touch()
            def execute(command,**kwargs):
                secret=kwargs['env']['XUCAI_KEYTOOL_PASSWORD']
                self.assertTrue(all(secret not in arg for arg in command))
                Path(command[command.index('-keystore')+1]).write_bytes(b'fake')
                return type('Result',(),{'returncode':0})()
            with patch.object(g.subprocess,'run',side_effect=execute):
                result=g.generate(Path(d)/'jdk',Path(d)/'.runtime/keys')
            self.assertNotIn('password',str(result).lower())
            self.assertTrue(Path(result['environmentFile']).is_file())


if __name__=='__main__':unittest.main()
