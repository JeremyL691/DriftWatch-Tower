#!/usr/bin/env python3
"""Assert actual pulled image application content and record architecture mapping."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import zipfile

p=argparse.ArgumentParser();p.add_argument('--context',required=True);p.add_argument('--image',required=True);p.add_argument('--out',required=True);args=p.parse_args()
c=json.loads(Path(args.context).read_text())
def run(*args):return subprocess.check_output(args,text=True).strip()
identity=json.loads(run('docker','image','inspect',args.image))[0]
container=run('docker','create',args.image)
try:
    with tempfile.TemporaryDirectory() as temp:
        jar=Path(temp)/'app.jar'
        run('docker','cp',container+':/app/app.jar',str(jar))
        digest=hashlib.sha256()
        with zipfile.ZipFile(jar) as archive:
            for entry in sorted(archive.infolist(),key=lambda x:x.filename):
                digest.update(f'{entry.filename}\0{entry.file_size}\0{entry.CRC}\n'.encode())
        actual=digest.hexdigest()
        if actual != c['content_identity']['jar_content_hash']:raise ValueError('public image application identity differs from frozen candidate')
        mapping={'local_image_id':c['image_id'],'public_reference':args.image,'public_image_id':identity['Id'],'public_platform':identity['Os']+'/'+identity['Architecture'],'jar_content_hash':actual,'jar_bytes_sha256':hashlib.sha256(jar.read_bytes()).hexdigest()}
        Path(args.out).write_text(json.dumps(mapping,indent=2))
finally:
    subprocess.run(['docker','rm','-f',container],capture_output=True,check=True)
