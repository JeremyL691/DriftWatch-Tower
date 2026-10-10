"""Negative controls for candidate-scoped vulnerability applicability."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
spec=importlib.util.spec_from_file_location('xslt',Path(__file__).resolve().parents[1]/'phases/check-xslt-reachability.py')
policy=importlib.util.module_from_spec(spec);spec.loader.exec_module(policy)

class XsltReachabilitySafety(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name)
        self.proof={'status':'PASSED','jar_content_hash':policy.CONTENT,'xslt_application_references':[], 'xslt_runtime_beans':[], 'xslt_runtime_handlers':[], 'http_probes':[403,403,403],
                    'sse_application_references':[], 'sse_runtime_handlers':[],
                    'spring_webflux_present':False}
        bodies={'beans.json':{'contexts':{'app':{'beans':{'controller':{'type':'com.driftwatch.api.AlertController'}}}}},'mappings.json':{'DispatcherServlet':{}},'application-reference-check.json':{'jar_content_hash':policy.CONTENT,'references':[],
                                                    'sse_references':[], 'spring_webflux_present':False},'http-probes.json':[{'status':403,'body':json.dumps({'error':'access denied'})}]*3}
        self.proof['artifact_sha256']={}
        for n,data in bodies.items():
            p=self.root/n;p.write_text(json.dumps(data));self.proof['artifact_sha256'][n]=policy.sha(p)
        self.write_proof()
        self.v={'VulnerabilityID':policy.CVE,'PkgName':'org.springframework:spring-webmvc','InstalledVersion':'6.2.19','Severity':'CRITICAL'}
    def write_proof(self):
        (self.root/'report.json').write_text(json.dumps(self.proof))
    def test_complete_bound_proof_passes(self):
        policy.validate_proof(self.root/'report.json')
    def test_other_candidate_is_not_eligible(self):
        self.proof['jar_content_hash']='different';self.assertFalse(policy.eligible(self.v,self.proof))
    def test_other_cve_or_package_version_cannot_be_assessed(self):
        for k,v in [('VulnerabilityID','CVE-other'),('PkgName','other'),('InstalledVersion','6.2.18')]:
            candidate={**self.v,k:v};self.assertFalse(policy.eligible(candidate,self.proof))
    def test_xslt_reference_bean_or_handler_blocks(self):
        for k in ['xslt_application_references','xslt_runtime_beans','xslt_runtime_handlers']:
            proof={**self.proof,k:['XsltView']};self.assertFalse(policy.eligible(self.v,proof))
    def test_sse_uses_require_application_and_runtime_proof(self):
        v = {**self.v, 'VulnerabilityID': policy.SSE_CVE}
        self.assertTrue(policy.eligible(v, self.proof))
        for field, value in [('sse_application_references', ['SseEmitter']),
                             ('sse_runtime_handlers', ['stream']),
                             ('spring_webflux_present', True)]:
            with self.subTest(field=field):
                self.assertFalse(policy.eligible(v, {**self.proof, field:value}))

    def test_application_sse_mapping_blocks_and_framework_mapping_does_not(self):
        def handler(owner, descriptor):
            return {'handler':'test',
                    'details':{'handlerMethod':{'className':owner,'descriptor':descriptor},
                               'requestMappingConditions':{'produces':[]}}}
        mapping={'contexts':{'app':{'mappings':{'dispatcherServlets':{'dispatcherServlet':[
            handler('com.driftwatch.api.EventController', '()Lorg/springframework/web/servlet/view/FragmentsRendering;')
        ]}}}}}
        self.assertEqual(len(policy.sse_mapping_findings(mapping)), 1)
        mapping['contexts']['app']['mappings']['dispatcherServlets']['dispatcherServlet'] = [
            handler('org.springframework.boot.autoconfigure.web.servlet.error.BasicErrorController',
                    '()Lorg/springframework/web/servlet/ModelAndView;')
        ]
        self.assertEqual(policy.sse_mapping_findings(mapping), [])

    def test_sse_finding_requires_same_exact_runtime_proof(self):
        scan=self.root/'scan.json'
        scan.write_text(json.dumps({'Results':[{'Vulnerabilities':[
            self.v,{**self.v,'VulnerabilityID':policy.SSE_CVE}
        ]}]}))
        result=policy.scan_findings(scan,self.proof)
        self.assertEqual(len(result['not_affected']),2)
        self.assertEqual(result['blocking'],[])
        self.proof['sse_runtime_handlers']=['unsafe SSE handler']
        result=policy.scan_findings(scan,self.proof)
        self.assertEqual(len(result['not_affected']),1)
        self.assertEqual(len(result['blocking']),1)

    def test_successful_arbitrary_view_probe_blocks(self):
        self.proof['http_probes']=[200,403,403];self.assertFalse(policy.eligible(self.v,self.proof))
    def test_raw_artifact_change_blocks(self):
        (self.root/'beans.json').write_text('{}')
        with self.assertRaises(ValueError):policy.validate_proof(self.root/'report.json')
    def test_missing_proof_file_blocks(self):
        (self.root/'mappings.json').unlink()
        with self.assertRaises(OSError):policy.validate_proof(self.root/'report.json')
    def test_other_critical_finding_still_blocks(self):
        p=self.root/'scan.json';p.write_text(json.dumps({'Results':[{'Vulnerabilities':[self.v,{**self.v,'VulnerabilityID':'CVE-other'}]}]}))
        r=policy.scan_findings(p,self.proof);self.assertEqual(len(r['not_affected']),1);self.assertEqual(len(r['blocking']),1)
    def test_scan_of_another_runtime_image_blocks(self):
        p=self.root/'scan.json';p.write_text(json.dumps({'ArtifactType':'container_image','ArtifactName':'other:image','Results':[{'Vulnerabilities':[self.v]}]}))
        self.proof['image_reference']='accepted:image'
        with self.assertRaises(ValueError):policy.scan_findings(p,self.proof)
    def test_empty_or_invalid_scan_fails_closed(self):
        p=self.root/'scan.json';p.write_text('{}')
        with self.assertRaises(ValueError):policy.scan_findings(p,self.proof)
        p.write_text('{')
        with self.assertRaises(ValueError):policy.scan_findings(p,self.proof)
