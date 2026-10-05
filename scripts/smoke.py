"""Real HTTP and source-launcher verification; uses only Python's standard library."""
import json, os, socket, subprocess, tempfile, time, urllib.request, urllib.error
from pathlib import Path
root=Path(__file__).resolve().parent.parent
base='http://127.0.0.1:18080'
# Fail before starting if another app owns our test port.
with socket.socket() as probe:
    probe.bind(('127.0.0.1',18080))

def generate(*args, expected=0):
    r=subprocess.run([str(root/'scripts/generate'),*args],cwd=root,text=True,capture_output=True,timeout=15)
    assert r.returncode==expected,(r.returncode,r.stdout,r.stderr)
    return r.stdout

def request(path, body=None, key=None):
    headers={'Content-Type':'application/json'}
    if key: headers['Idempotency-Key']=key
    req=urllib.request.Request(base+path,data=None if body is None else json.dumps(body).encode(),headers=headers)
    try:
        with urllib.request.urlopen(req,timeout=2) as response:
            return response.status,json.load(response)
    except urllib.error.HTTPError as e:
        return e.code,json.load(e)

def start(log):
    process=subprocess.Popen([os.environ['JAVA_HOME']+'/bin/java','-jar',str(root/'target/queuedesk-0.1.0.jar'),'--server.port=18080'],cwd=root,stdout=log,stderr=log)
    try:
        for _ in range(100):
            if process.poll() is not None: raise AssertionError('API stopped during startup; inspect smoke log')
            try:
                if request('/api/v1/instructions')[0]==200: return process
            except (OSError,urllib.error.URLError): pass
            time.sleep(.1)
        raise AssertionError('API startup timeout')
    except BaseException:
        stop(process)
        raise

def stop(process):
    process.terminate()
    try: process.wait(timeout=10)
    except subprocess.TimeoutExpired: process.kill(); process.wait()

assert generate('--count','10')==generate('--count','10')
for scenario in ['normal','duplicate','invalid','burst']:
    output=generate('--scenario',scenario,'--count','10')
    lines=output.splitlines()[1:]
    assert len(lines)==10
    for line in lines: json.loads(line.split(' ',1)[1])
generate('--send','--base-url','https://example.com',expected=1)
generate('--count','501',expected=1)
generate('--rate','11',expected=1)
generate('--run-id','unsafe/name',expected=1)
with tempfile.TemporaryFile() as log:
    process=start(log)
    try:
        for scenario,count in [('normal','3'),('duplicate','5'),('invalid','4'),('burst','3')]:
            output=generate('--send','--scenario',scenario,'--count',count,'--rate','10','--run-id','smoke-'+scenario,'--base-url',base)
            assert 'unknown=0 failed=0 unsent=0' in output,output
            if scenario=='duplicate': assert 'accepted=4 repeated=1' in output,output
            if scenario=='invalid': assert 'invalid=4' in output,output
            print(output.strip())
        status,rows=request('/api/v1/instructions?runId=smoke-normal')
        assert status==200 and len(rows)==3
        first=rows[0]
        status,item=request(first['statusUrl'])
        assert status==200 and item['status']=='RECEIVED' and item['delivery']=='NOT_CONFIGURED'
        body=first['payload']; key=body['clientReference']
        status,repeated=request('/api/v1/instructions',body,key)
        assert status==200 and repeated['instructionId']==first['instructionId']
        body['amountMinor']+=1
        assert request('/api/v1/instructions',body,key)[0]==409
        body['unknownField']='bad'
        assert request('/api/v1/instructions',body,'unknown-field')[0]==400
        assert request('/api/v1/instructions?runId=smoke-invalid')[1]==[]
        assert request('/api/v1/instructions/00000000-0000-0000-0000-000000000000')[0]==404
        assert request('/api/v1/instructions?limit=101')[0]==400
    finally: stop(process)
    process=start(log)
    try:
        assert request(first['statusUrl'])[0]==404
        assert request('/api/v1/instructions')[1]==[]
    finally: stop(process)
print('PASS: deterministic CLI, local allowlist/caps, all four scenarios, live HTTP admission/repeat/conflict/validation/GET, restart loss; both test servers stopped.')
