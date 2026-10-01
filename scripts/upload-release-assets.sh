#!/usr/bin/env bash
# Upload only explicitly bound assets, preserving the bundle bytes accepted by G15.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/common.sh"
CONTEXT=""; ARTIFACTS=""; EVIDENCE=""; VERSION="v1.0.0"; DIGEST=""; UPDATE_METADATA=0
while [ $# -gt 0 ]; do
  case "$1" in
    --context) CONTEXT="$2"; shift 2 ;;
    --artifacts) ARTIFACTS="$2"; shift 2 ;;
    --evidence) EVIDENCE="$2"; shift 2 ;;
    --version) VERSION="$2"; shift 2 ;;
    --digest) DIGEST="$2"; shift 2 ;;
    --update-metadata) UPDATE_METADATA=1; shift ;;
    *) die "unknown argument: $1" ;;
  esac
done
[ -n "$CONTEXT" ] && [ -d "$ARTIFACTS" ] && [ -f "$EVIDENCE" ] || die "requires --context FILE --artifacts DIR --evidence explicit-tar-file"
require_cmd gh
python3 "$SCRIPT_DIR/release-context.py" --context "$CONTEXT" >/dev/null
python3 - "$SCRIPT_DIR" "$CONTEXT" "$ARTIFACTS" "$EVIDENCE" "$VERSION" "$DIGEST" "$UPDATE_METADATA" <<'PY'
import hashlib, importlib.util, json, pathlib, re, shutil, subprocess, sys, tempfile
scripts, context_path, artifacts, evidence, version, supplied_digest, update = sys.argv[1:]
spec=importlib.util.spec_from_file_location('context',pathlib.Path(scripts)/'release-context.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
c,gates=module.validate(context_path)
root=pathlib.Path(artifacts).resolve()
digest=c.get('public_image_digest')
if not digest or not re.fullmatch('sha256:[a-f0-9]{64}',digest) or supplied_digest and supplied_digest != digest:
    raise ValueError('digest must be recorded in context and match --digest')
module.same_surface(c.get('release_sha'),c['candidate_application_sha'])
def gh(*args):return subprocess.check_output(['gh',*args],text=True)
released=gh('api',f'repos/:owner/:repo/commits/{version}','--jq','.sha').strip()
if released != c['release_sha']:raise ValueError('existing tag does not name designated release SHA')
release=json.loads(gh('release','view',version,'--json','url,assets'))
bundle=f'driftwatch-tower-{version}-bundle.tar.gz'
summary=module.read(str(pathlib.Path(gates['G15']['path']).parent/'install-summary.json'))
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
if summary.get('bundle_sha256') != sha(root/bundle):raise ValueError('bundle bytes differ from G15')
manifest_path=root/'release-manifest.json'
manifest=json.loads(manifest_path.read_text())
for key in ['source_tree_hash','config_hash','dependency_lock_hash','content_identity']:
    if manifest.get(key)!=c[key]:raise ValueError(f'package manifest mismatch: {key}')
manifest['release_commit']=c['release_sha']
manifest['image']['published_digest']=digest
manifest['image']['published_reference']=manifest['image']['registry']+'@'+digest
manifest['image']['publication_mapping']=c.get('publication_mapping') or {}
manifest_path.write_text(json.dumps(manifest,indent=2))
evidence=pathlib.Path(evidence).resolve()
evidence_report=json.loads(pathlib.Path(str(evidence)+'.json').read_text())
if evidence_report.get('status')!='PASSED' or evidence_report.get('remaining_credentials')!=0 or evidence_report.get('archive_sha256')!=sha(evidence) or evidence_report.get('source_tree_hash')!=c['source_tree_hash'] or evidence_report.get('soak_run_id')!=c['soak']['run_id'] or evidence_report.get('gates')!=c['gates']:
    raise ValueError('evidence archive lacks a matching sanitized binding report')
shutil.copyfile(evidence,root/evidence.name) if evidence.parent != root else None
names=['release-manifest.json',bundle,manifest['sbom'],evidence.name]
for name in c.get('supplementary_assets',[]):
    if pathlib.Path(name).name!=name:raise ValueError('unsafe supplementary attachment name')
    names.append(name)
for name in names:
    if not (root/name).is_file():raise ValueError(f'missing attachment: {name}')
(root/'checksums.txt').write_text(''.join(f'{sha(root/name)}  {name}\n' for name in names))
names.append('checksums.txt')
expected={name:sha(root/name) for name in names}
existing={asset['name']:asset for asset in release['assets']}
previous=c.get('assets',{})
base=release['url'].replace('/releases/tag/','/releases/download/')
with tempfile.TemporaryDirectory() as temp:
    for name in names:
        if name not in existing:continue
        target=pathlib.Path(temp)/name
        subprocess.run(['curl','-q','--fail','--location','--max-time','300','--output',str(target),base+'/'+name],check=True,capture_output=True)
        remote=sha(target)
        if remote==expected[name]:continue
        if update=='1' and name in ['release-manifest.json','checksums.txt'] and previous.get(name)==remote:
            continue
        raise ValueError(f'conflicting existing attachment; refusing overwrite: {name}')
c['previous_assets']=previous;c['assets']=expected
c.update(bundle_asset=bundle,sbom_asset=manifest['sbom'],evidence_asset=evidence.name)
module.file_path(context_path).write_text(json.dumps(c,indent=2))
module.validate(context_path,published=True)
# Every potential overwrite above has been compared to a known hash.
subprocess.run(['gh','release','upload',version,*[str(root/name) for name in names],'--clobber'],check=True)
print(f'uploaded {len(names)} explicitly checked assets; immutable bundle {expected[bundle]}')
PY
