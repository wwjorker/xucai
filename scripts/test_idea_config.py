import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
import tempfile
from unittest.mock import patch
from prepare_idea_config import properties, ROOT


class IdeaConfigTest(unittest.TestCase):
    def test_source_package_generation_refuses_unintentional_overwrite(self):
        import prepare_idea_config as config
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / '.gitignore').write_text('.runtime/\n', encoding='utf8')
            source = root / 'source.json'
            source.write_text('{"XUCAI_TEST":"local-only"}', encoding='utf8')
            output = root / '.runtime/idea-private.properties'
            with patch.object(config, 'ROOT', root), patch.object(config, 'OUTPUT', output):
                config.prepare(source)
                self.assertIn('XUCAI_TEST=local-only', output.read_text())
                with self.assertRaises(RuntimeError): config.prepare(source)
                source.write_text('{"XUCAI_TEST":"updated"}', encoding='utf8')
                config.prepare(source, overwrite=True)
                self.assertIn('XUCAI_TEST=updated', output.read_text())

    def test_properties_escape_paths_and_control_characters(self):
        value = properties({'XUCAI_TEST': 'F:\\目录\\key file\nnext=#!'})
        self.assertIn(r'F\:\\\u76ee\u5f55\\key\ file\nnext\=\#\!', value)
        self.assertEqual(2, len(value.splitlines()))

    def test_rejects_non_configuration_keys_and_nested_values(self):
        for value in ({'spring.datasource.url': 'bad'}, {'XUCAI_TEST': {}}, []):
            with self.assertRaises(ValueError): properties(value)

    def test_run_configurations_reference_private_file_without_credentials(self):
        configs = list((ROOT / '.run').glob('*.run.xml'))
        self.assertEqual(8, len(configs))
        applications = []
        for path in configs:
            config = ET.parse(path).getroot().find('configuration')
            self.assertIsNone(config.find('envs'))
            if config.get('type') != 'Application': continue
            applications.append(config.get('name'))
            options = {x.get('name'): x.get('value') for x in config.findall('option')}
            self.assertIn('--spring.profiles.active=portable,xucai', options['PROGRAM_PARAMETERS'])
            self.assertIn('file:$PROJECT_DIR$/.runtime/idea-private.properties', options['PROGRAM_PARAMETERS'])
            self.assertNotIn('optional:', options['PROGRAM_PARAMETERS'])
            main = options['MAIN_CLASS_NAME'].replace('.', '/') + '.java'
            module = config.find('module').get('name')
            directory = ROOT / ('xucai-auth/' + module if module == 'xucai-auth-service' else module)
            self.assertTrue((directory / 'src/main/java' / main).is_file())
        compound = ET.parse(ROOT / '.run/all.run.xml').getroot().find('configuration')
        self.assertEqual(set(applications), {x.get('name') for x in compound.findall('toRun')})


if __name__ == '__main__': unittest.main()
