"""Guard wrong-candidate image exports without containers or networks."""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('image_export', Path(__file__).parents[1] / 'phases/check-image-export.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ImageExportSafety(unittest.TestCase):
    def fixture(self, directory, oci=False, mutate=None):
        config = b'{"architecture":"arm64","config":{}}'
        config_id = 'sha256:' + hashlib.sha256(config).hexdigest()
        name = 'driftwatch-tower:csrf-v101'
        entries = {'config.json': config, 'manifest.json': json.dumps([
            {'Config': 'config.json', 'RepoTags': [name], 'Layers': []}]).encode()}
        identity = config_id
        if oci:
            blob = b'{"schemaVersion":2,"manifests":[]}'
            identity = 'sha256:' + hashlib.sha256(blob).hexdigest()
            entries['blobs/sha256/' + identity.split(':')[1]] = blob
            entries['index.json'] = json.dumps({'schemaVersion': 2, 'manifests': [{'digest': identity}]}).encode()
        if mutate:
            mutate(entries)
        path = Path(directory) / 'image.tar'
        with tarfile.open(path, 'w') as archive:
            for key, value in entries.items():
                member = tarfile.TarInfo(key); member.size = len(value)
                archive.addfile(member, io.BytesIO(value))
        return path, identity, name

    def check(self, oci=False, mutate=None, override_id=None, override_name=None):
        with tempfile.TemporaryDirectory() as directory:
            path, identity, name = self.fixture(directory, oci, mutate)
            return module.verify_export(path, override_id or identity, override_name or name)

    def test_new_candidate_tag_does_not_require_old_local_tag(self):
        self.assertEqual(self.check()['image_name'], 'driftwatch-tower:csrf-v101')

    def test_oci_image_id_is_distinct_from_config_id(self):
        result = self.check(oci=True)
        self.assertEqual(result['identity_kind'], 'oci-index')
        self.assertNotEqual(result['image_id'], result['config_id'])

    def test_wrong_classic_candidate_fails(self):
        with self.assertRaisesRegex(ValueError, 'differs'):
            self.check(override_id='sha256:' + 'f' * 64)

    def test_wrong_oci_candidate_fails(self):
        with self.assertRaisesRegex(ValueError, 'differs'):
            self.check(oci=True, override_id='sha256:' + 'f' * 64)

    def test_old_tag_cannot_stand_in_for_exported_reference(self):
        with self.assertRaisesRegex(ValueError, 'declared'):
            self.check(override_name='driftwatch-tower:local')

    def test_corrupt_oci_identity_fails(self):
        def corrupt(entries):
            key = next(x for x in entries if x.startswith('blobs/'))
            entries[key] = b'corrupted'
        with self.assertRaisesRegex(ValueError, 'corrupted'):
            self.check(oci=True, mutate=corrupt)

    def test_invalid_json_and_multiple_images_fail_closed(self):
        for data in [b'invalid', b'[]', b'[{},{ }]', b'{}']:
            with self.subTest(data=data), self.assertRaises(ValueError):
                self.check(mutate=lambda entries: entries.update({'manifest.json': data}))

    def test_missing_or_unsafe_config_fails_closed(self):
        for config in ['missing.json', '../config.json']:
            with self.subTest(config=config), self.assertRaises(ValueError):
                self.check(mutate=lambda entries: entries.update({'manifest.json': json.dumps([
                    {'Config': config, 'RepoTags': ['driftwatch-tower:csrf-v101']}]).encode()}))


if __name__ == '__main__':
    unittest.main()
