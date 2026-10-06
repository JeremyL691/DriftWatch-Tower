#!/usr/bin/env python3
"""Verify the frozen image's identity inside a Docker save archive before loading it."""
import argparse
import hashlib
import json
from pathlib import PurePosixPath, Path
import re
import sys
import tarfile


def verify_export(path, image_id, image_name):
    if not re.fullmatch(r'sha256:[0-9a-f]{64}', image_id):
        raise ValueError('invalid frozen image ID')
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.:/@-]*', image_name):
        raise ValueError('invalid exported image reference')
    with tarfile.open(path) as archive:
        def read(name):
            if PurePosixPath(name).is_absolute() or '..' in PurePosixPath(name).parts:
                raise ValueError('unsafe archive identity path')
            matches = [m for m in archive.getmembers() if m.name == name]
            if len(matches) != 1 or not matches[0].isfile() or matches[0].size > 2_000_000:
                raise ValueError('missing, duplicate or unsafe identity member: ' + name)
            return archive.extractfile(matches[0]).read()
        manifest = json.loads(read('manifest.json'))
        if not isinstance(manifest, list) or len(manifest) != 1 or not isinstance(manifest[0], dict):
            raise ValueError('export must contain exactly one image')
        record = manifest[0]
        tags = record.get('RepoTags') or []
        if not isinstance(tags, list) or (image_name != image_id and image_name not in tags):
            raise ValueError('export does not contain the declared image reference')
        config = read(record['Config'])
        if not isinstance(json.loads(config), dict):
            raise ValueError('invalid exported image configuration')
        config_id = 'sha256:' + hashlib.sha256(config).hexdigest()
        if any(m.name == 'index.json' for m in archive.getmembers()):
            # Containerd exports identify an image by its OCI index, while classic Docker
            # archives identify it by the configuration blob. Do not conflate these IDs.
            index = json.loads(read('index.json'))
            descriptors = index.get('manifests') if isinstance(index, dict) else None
            if index.get('schemaVersion') != 2 or not isinstance(descriptors, list) or len(descriptors) != 1:
                raise ValueError('invalid or ambiguous OCI export index')
            if descriptors[0].get('digest') != image_id:
                raise ValueError('OCI export identity differs from frozen image')
            blob = read('blobs/sha256/' + image_id.split(':', 1)[1])
            if 'sha256:' + hashlib.sha256(blob).hexdigest() != image_id:
                raise ValueError('OCI identity blob is corrupted')
            if not isinstance(json.loads(blob), dict):
                raise ValueError('invalid OCI identity blob')
            identity_kind = 'oci-index'
        else:
            if config_id != image_id:
                raise ValueError('Docker export configuration differs from frozen image')
            identity_kind = 'docker-config'
        return {'status': 'PASSED', 'image_id': image_id, 'image_name': image_name,
                'identity_kind': identity_kind, 'config_id': config_id, 'repo_tags': tags}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tar', required=True)
    parser.add_argument('--image-id', required=True)
    parser.add_argument('--image-name', required=True)
    parser.add_argument('--out', required=True)
    args = parser.parse_args()
    try:
        result = verify_export(args.tar, args.image_id, args.image_name)
    except (ValueError, KeyError, TypeError, AttributeError, OSError, tarfile.TarError) as error:
        result = {'status': 'FAILED', 'error': str(error)}
    Path(args.out).write_text(json.dumps(result, indent=2))
    print(json.dumps(result))
    return 0 if result['status'] == 'PASSED' else 1


if __name__ == '__main__':
    raise SystemExit(main())
