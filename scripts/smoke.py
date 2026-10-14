"""Smoke verification for the packaged app over real HTTP.

Boots the jar on a scratch port with the publisher and worker off, so
admissions stay RECEIVED and can be asserted exactly: generator
determinism, loopback allowlist and bounds, all eight scenarios over
live HTTP, repeat/conflict semantics, validation, and restart survival.
Standard library only. Requires Postgres up (scripts/postgres up).
"""
import json, os, socket, subprocess, tempfile, time, urllib.request, urllib.error
from pathlib import Path
root=Path(__file__).resolve().parent.parent
base='http://127.0.0.1:18080'
# Fail before starting if another app owns our test port.
with socket.socket() as probe:
    probe.bind(('127.0.0.1',18080))

def generate(*args, expected=0):
    """Run the generator CLI and assert its exit code; returns stdout for report assertions."""
    r=subprocess.run([str(root/'scripts/generate'),*args],cwd=root,text=True,capture_output=True,timeout=15)
    assert r.returncode==expected,(r.returncode,r.stdout,r.stderr)
    return r.stdout

def request(path, body=None, key=None):
    """One HTTP call returning (status, parsed JSON); HTTP errors are values, not exceptions."""
    headers={'Content-Type':'application/json'}
    if key: headers['Idempotency-Key']=key
    req=urllib.request.Request(base+path,data=None if body is None else json.dumps(body).encode(),headers=headers)
    try:
        with urllib.request.urlopen(req,timeout=2) as response:
            return response.status,json.load(response)
    except urllib.error.HTTPError as e:
        return e.code,json.load(e)

def start(log):
    """Boot the packaged jar on the smoke port (publisher/worker off) and wait for readiness."""
    process=subprocess.Popen([os.environ['JAVA_HOME']+'/bin/java','-jar',str(root/'target/queuedesk-0.1.0.jar'),'--server.port=18080','--queuedesk.publisher.enabled=false','--queuedesk.worker.enabled=false'],cwd=root,stdout=log,stderr=log)
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
    """Terminate the test server, escalating to kill after a bounded wait."""
    process.terminate()
    try: process.wait(timeout=10)
    except subprocess.TimeoutExpired: process.kill(); process.wait()

assert generate('--count','10')==generate('--count','10')
for scenario in ['normal','duplicate','invalid','burst','conflict','reject','uncertain','unknown']:
    output=generate('--scenario',scenario,'--count','10')
    lines=output.splitlines()[1:]
    expected_lines=12 if scenario=='duplicate' else 10
    assert len(lines)==expected_lines,(scenario,len(lines))
    for line in lines: json.loads(line.split(' ',1)[1])
generate('--send','--base-url','https://example.com',expected=1)
generate('--count','501',expected=1)
generate('--rate','11',expected=1)
generate('--run-id','unsafe/name',expected=1)
with tempfile.TemporaryFile() as log:
    try:
        with socket.create_connection(('127.0.0.1',5442),timeout=2): pass
    except OSError:
        raise SystemExit('Postgres is not reachable at 127.0.0.1:5442; start it with ./scripts/postgres up')
    tag=f"{os.getpid()}-{int(time.time())}"
    def runid(scenario): return f"smoke-{scenario}-{tag}"
    process=start(log)
    try:
        for scenario,count in [('normal','3'),('duplicate','5'),('invalid','4'),('burst','3'),('conflict','4'),('reject','3'),('uncertain','4'),('unknown','3')]:
            output=generate('--send','--scenario',scenario,'--count',count,'--rate','10','--run-id',runid(scenario),'--base-url',base)
            assert 'unknown=0 failed=0 unsent=0' in output,output
            if scenario=='duplicate': assert 'accepted=5 repeated=1' in output,output
            if scenario=='invalid': assert 'invalid=4' in output,output
            if scenario=='conflict': assert 'attempted=4 accepted=2' in output,output
            if scenario=='reject': assert 'accepted=3' in output,output
            if scenario=='uncertain': assert 'accepted=4' in output,output
            if scenario=='unknown': assert 'accepted=3' in output,output
            print(output.strip())
        status,rows=request('/api/v1/instructions?runId='+runid('normal'))
        assert status==200 and len(rows)==3
        first=rows[0]
        status,item=request(first['statusUrl'])
        assert status==200 and item['status']=='RECEIVED' and item['delivery']=='PENDING_PUBLISH'
        body=first['payload']; key=body['clientReference']
        status,repeated=request('/api/v1/instructions',body,key)
        assert status==200 and repeated['instructionId']==first['instructionId']
        body['amountMinor']+=1
        assert request('/api/v1/instructions',body,key)[0]==409
        body['unknownField']='bad'
        assert request('/api/v1/instructions',body,'unknown-field')[0]==400
        assert request('/api/v1/instructions?runId='+runid('invalid'))[1]==[]
        assert request('/api/v1/instructions/00000000-0000-0000-0000-000000000000')[0]==404
        assert request('/api/v1/instructions?limit=101')[0]==400
    finally: stop(process)
    process=start(log)
    try:
        status,survived=request(first['statusUrl'])
        assert status==200 and survived['instructionId']==first['instructionId'] and survived['status']=='RECEIVED'
        assert len(request('/api/v1/instructions?runId='+runid('normal'))[1])==3
    finally: stop(process)
print('PASS: deterministic CLI, local allowlist/caps, all eight scenarios, live HTTP admission/repeat/conflict/validation/GET, restart survival; both test servers stopped.')
