#!/usr/bin/env python3
"""Candidate-bound CVE-2026-47884 reachability proof, never a general CVE waiver."""
import argparse
import base64
import datetime
import hashlib
import json
import pathlib
import subprocess
import time
import urllib.request
import urllib.error
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
CVE = 'CVE-2026-47884'
CONTENT = 'f06767a9fc46fc1a0b9d8a00c9c1fb9a5071e6a2e33173692a70096cee28ffe0'
PURL = 'pkg:maven/org.springframework/spring-webmvc@6.2.19'

def sha(path):
    return hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()

def eligible(vulnerability, proof):
    return (vulnerability.get('VulnerabilityID') == CVE
            and vulnerability.get('PkgName') == 'org.springframework:spring-webmvc'
            and vulnerability.get('InstalledVersion') == '6.2.19'
            and proof.get('status') == 'PASSED'
            and proof.get('jar_content_hash') == CONTENT
            and proof.get('xslt_application_references') == []
            and proof.get('xslt_runtime_beans') == []
            and proof.get('xslt_runtime_handlers') == []
            and proof.get('http_probes') == [403, 403, 403])

def validate_proof(path):
    path = pathlib.Path(path)
    proof = json.loads(path.read_text())
    if not eligible({'VulnerabilityID': CVE, 'PkgName':'org.springframework:spring-webmvc',
                     'InstalledVersion':'6.2.19'}, proof):
        raise ValueError('missing or invalid candidate-bound XSLT reachability proof')
    for name in ['beans.json','mappings.json','application-reference-check.json','http-probes.json']:
        if proof.get('artifact_sha256',{}).get(name) != sha(path.parent/name):
            raise ValueError('changed reachability artifact: '+name)
    beans = json.loads((path.parent/'beans.json').read_text())
    mappings = json.loads((path.parent/'mappings.json').read_text())
    if 'Xslt' in json.dumps(beans) or 'Xslt' in json.dumps(mappings):
        raise ValueError('XSLT reachable in runtime artifacts')
    ref = json.loads((path.parent/'application-reference-check.json').read_text())
    if ref.get('jar_content_hash') != CONTENT or ref.get('references') != []:
        raise ValueError('changed application/config reference proof')
    probes=json.loads((path.parent/'http-probes.json').read_text())
    if len(probes)!=3 or any(r.get('status')!=403 or json.loads(r.get('body','{}')).get('error')!='access denied' for r in probes):
        raise ValueError('arbitrary view probe was not explicitly denied')
    return proof

def scan_findings(path, proof):
    raw=json.loads(pathlib.Path(path).read_text())
    if not raw.get('Results'):
        raise ValueError('scan evaluated no targets: '+str(path))
    if raw.get('ArtifactType')=='container_image' and raw.get('ArtifactName')!=proof.get('image_reference'):
        raise ValueError('raw scan does not name the runtime-proven image')
    blocking=[];not_affected=[]
    for result in raw['Results']:
        for v in result.get('Vulnerabilities') or []:
            if v.get('Severity') not in ['HIGH','CRITICAL']: continue
            entry={k:v.get(k) for k in ['VulnerabilityID','PkgName','InstalledVersion','FixedVersion','Severity']}
            if eligible(v,proof): not_affected.append(entry)
            else: blocking.append(entry)
    return {'blocking':blocking,'not_affected':not_affected,'raw_scan_sha256':sha(path)}

def prove(image, out, port):
    out=pathlib.Path(out).resolve();out.mkdir(parents=True,exist_ok=True)
    if (out/'report.json').exists(): raise ValueError('refuse overwrite of prior reachability proof')
    image_id=subprocess.check_output(['docker','image','inspect','--format','{{.Id}}',image],text=True).strip()
    container=subprocess.check_output(['docker','create',image],text=True).strip()
    try: subprocess.run(['docker','cp',container+':/app/app.jar',str(out/'app.jar')],check=True,stdout=subprocess.DEVNULL)
    finally: subprocess.run(['docker','rm',container],check=True,stdout=subprocess.DEVNULL)
    digest=hashlib.sha256();references=[]
    with zipfile.ZipFile(out/'app.jar') as jar:
        for entry in sorted(jar.infolist(),key=lambda e:e.filename):
            digest.update(f'{entry.filename}\0{entry.file_size}\0{entry.CRC}\n'.encode())
            if entry.filename.startswith('BOOT-INF/classes/') and not entry.is_dir():
                data=jar.read(entry).lower()
                if any(word in data for word in [b'xslt',b'xsl:stylesheet',b'javax.xml.transform',b'view-class',b'view.class']):
                    references.append(entry.filename)
        if 'BOOT-INF/lib/spring-webmvc-6.2.19.jar' not in jar.namelist():
            raise ValueError('proof is scoped only to spring-webmvc 6.2.19')
    content=digest.hexdigest()
    if content!=CONTENT or references: raise ValueError('changed candidate or XSLT application/config reference')
    (out/'application-reference-check.json').write_text(json.dumps({'jar_content_hash':content,'references':references},indent=2))
    project='dwt-xslt-proof-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dt%H%M%Sz')
    if subprocess.check_output(['docker','ps','-aq','--filter','label=com.docker.compose.project='+project],text=True).strip(): raise ValueError('project exists')
    if subprocess.check_output(['docker','volume','ls','-q','--filter','label=com.docker.compose.project='+project],text=True).strip(): raise ValueError('volumes exist')
    env=out/'runtime.env';subprocess.run([str(ROOT/'scripts/selfhost.sh'),'init','--env-file',str(env)],check=True,stdout=subprocess.DEVNULL)
    with env.open('a') as f:f.write('\nDWT_APP_PORT='+str(port)+'\nDWT_APP_IMAGE='+image_id+'\n')
    override=out/'diagnostics.yml';override.write_text('services:\n  app:\n    environment:\n      MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE: health,info,metrics,prometheus,beans,mappings\n')
    command=['docker','compose','-p',project,'--env-file',str(env),'-f',str(ROOT/'docker-compose.yml'),'-f',str(override)]
    try:
        with (out/'runtime-start.log').open('w') as log:
            subprocess.run(command+['up','-d','--no-build','--wait','--wait-timeout','240'],check=True,stdout=log,stderr=subprocess.STDOUT)
        cid=subprocess.check_output(command+['ps','-q','app'],text=True).strip()
        running=subprocess.check_output(['docker','inspect','--format','{{.Image}}',cid],text=True).strip()
        if running!=image_id:raise ValueError('running proof image differs')
        values={k:v for line in env.read_text().splitlines() if '=' in line and not line.startswith('#') for k,_,v in [line.partition('=')]}
        auth=base64.b64encode((values['DWT_ADMIN_USERNAME']+':'+values['DWT_ADMIN_PASSWORD']).encode()).decode()
        def get(path):
            req=urllib.request.Request('http://127.0.0.1:'+str(port)+path,headers={'Authorization':'Basic '+auth})
            try:
                with urllib.request.urlopen(req,timeout=15) as r:return r.status,r.read()
            except urllib.error.HTTPError as e:return e.code,e.read()
        artifacts={}
        for name in ['beans','mappings']:
            status,data=get('/actuator/'+name)
            if status!=200:raise ValueError('actual '+name+' endpoint returned '+str(status))
            artifacts[name]=json.loads(data);(out/(name+'.json')).write_text(json.dumps(artifacts[name],indent=2))
        allbeans=[v for context in artifacts['beans'].get('contexts',{}).values() for v in context.get('beans',{}).values()]
        if not any('com.driftwatch' in v.get('type','') for v in allbeans):raise ValueError('application beans absent')
        if 'DispatcherServlet' not in json.dumps(artifacts['mappings']):raise ValueError('servlet mappings absent')
        xsltbeans=[v for v in allbeans if 'Xslt' in json.dumps(v)];xslthandlers=['Xslt'] if 'Xslt' in json.dumps(artifacts['mappings']) else []
        probe_responses=[]
        for path in ['/reachability-probe-no-view.xsl','/reachability-probe-no-view','/xslt/there-is-no-stylesheet.xsl']:
            status,data=get(path);probe_responses.append({'path':path,'status':status,'body':data.decode('utf-8')})
        (out/'http-probes.json').write_text(json.dumps(probe_responses,indent=2))
        probes=[r['status'] for r in probe_responses]
        if xsltbeans or xslthandlers or probes!=[403,403,403]:raise ValueError('runtime XSLT condition or implicit view probe failed')
        report={'status':'PASSED','cve':CVE,'image_id':image_id,'image_reference':image,'jar_content_hash':content,'project':project,'observed_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'advisory':'https://spring.io/security/cve-2026-47884/','scope':'Unmodified accepted selfhost application; only diagnostic actuator endpoint exposure was added on the isolated proof stack','xslt_application_references':references,'xslt_runtime_beans':xsltbeans,'xslt_runtime_handlers':xslthandlers,'http_probes':probes,'artifact_sha256':{n:sha(out/n) for n in ['beans.json','mappings.json','application-reference-check.json','http-probes.json']}}
        (out/'report.json').write_text(json.dumps(report,indent=2));validate_proof(out/'report.json')
        vex={'@context':'https://openvex.dev/ns/v0.2.0','@id':'https://github.com/JeremyL691/DriftWatch-Tower/security/vex/'+content,'author':'DriftWatch Tower acceptance tooling','timestamp':report['observed_utc'],'version':1,'statements':[{'vulnerability':{'name':CVE},'products':[{'@id':PURL}],'status':'not_affected','justification':'vulnerable_code_not_in_execute_path','impact_statement':'The exact accepted application content '+content+' has no XSLT references/configuration; actual selfhost beans and handlers contain no XsltView or XsltViewResolver, and authenticated arbitrary view probes return403 JSON access-denied responses. Applies only to this candidate and default selfhost configuration; original CRITICAL finding is retained.'}]}
        (out/'openvex.json').write_text(json.dumps(vex,indent=2));print(json.dumps(report))
    finally:
        with (out/'runtime-cleanup.log').open('w') as log:subprocess.run(command+['down','-v'],stdout=log,stderr=subprocess.STDOUT,check=True)

def main():
    p=argparse.ArgumentParser();p.add_argument('--image');p.add_argument('--out',required=True);p.add_argument('--port',type=int,default=18118);p.add_argument('--proof');p.add_argument('--scan',action='append',default=[]);a=p.parse_args()
    if a.image:prove(a.image,a.out,a.port)
    if a.scan:
        proof=validate_proof(a.proof or pathlib.Path(a.out)/'report.json');results={s:scan_findings(s,proof) for s in a.scan};pathlib.Path(a.out,'scan-assessment.json').write_text(json.dumps(results,indent=2))
        if any(x['blocking'] for x in results.values()):raise ValueError('unfixed HIGH/CRITICAL runtime finding')
if __name__=='__main__':main()
