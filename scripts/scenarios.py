"""Core scenario suite (TEST-01): SC01-SC05, SC11-SC12 against a live local lab.
Boots the packaged app with publisher+worker ON, runs each generator scenario
with completion observation, asserts the PRD catalog results, and stores JSON
reports under reports/. Standard library only."""
import json, os, socket, subprocess, tempfile, time, urllib.request, urllib.error
from datetime import datetime, timezone
from pathlib import Path
root=Path(__file__).resolve().parent.parent
base='http://127.0.0.1:18081'
with socket.socket() as probe:
    probe.bind(('127.0.0.1',18081))

def generate(*args, expected=0, timeout=180):
    """Run the generator CLI and assert its exit code; returns stdout for report assertions."""
    r=subprocess.run([str(root/'scripts/generate'),*args],cwd=root,text=True,capture_output=True,timeout=timeout)
    assert r.returncode==expected,(r.returncode,r.stdout,r.stderr)
    return r.stdout

def request(path):
    """One GET returning (status, parsed JSON); HTTP errors are values, not exceptions."""
    req=urllib.request.Request(base+path,headers={'Content-Type':'application/json'})
    try:
        with urllib.request.urlopen(req,timeout=5) as response:
            return response.status,json.load(response)
    except urllib.error.HTTPError as e:
        return e.code,json.load(e)

def start(log, extra_env=None):
    """Boot the packaged jar on the scenario port (full pipeline on) and wait for readiness."""
    env=dict(os.environ)
    if extra_env: env.update(extra_env)
    process=subprocess.Popen([os.environ['JAVA_HOME']+'/bin/java','-jar',str(root/'target/queuedesk-0.1.0.jar'),'--server.port=18081'],cwd=root,stdout=log,stderr=log,env=env)
    try:
        for _ in range(150):
            if process.poll() is not None: raise AssertionError('API stopped during startup; inspect scenario log')
            try:
                if request('/api/v1/instructions')[0]==200: return process
            except (OSError,urllib.error.URLError): pass
            time.sleep(.2)
        raise AssertionError('API startup timeout')
    except BaseException:
        stop(process)
        raise

def stop(process):
    """Terminate the test server, escalating to kill after a bounded wait."""
    process.terminate()
    try: process.wait(timeout=15)
    except subprocess.TimeoutExpired: process.kill(); process.wait()

def run_summary(runid):
    """Fetch one run's accounting summary, asserting HTTP 200."""
    status,summary=request('/api/v1/runs/'+runid)
    assert status==200,(status,summary)
    return summary

# FAIL-01 lab guard: failure drills refuse to run unless the target is exactly
# this loopback lab with its named containers and QM. Never touches anything else.
def lab_guard():
    assert base.startswith('http://127.0.0.1') or base.startswith('http://localhost'),base
    for name in ('qdesk-postgres','qdesk-mq'):
        r=subprocess.run(['docker','inspect','-f','{{.Name}}',name],capture_output=True,text=True,timeout=30)
        if r.returncode!=0 or name not in r.stdout:
            raise SystemExit(f'REFUSED: named lab container {name} missing; failure drills run only against the local lab')
    r=subprocess.run(['docker','exec','qdesk-mq','dspmq','-m','QDESK'],capture_output=True,text=True,timeout=30)
    if 'QMNAME(QDESK)' not in r.stdout:
        raise SystemExit('REFUSED: lab queue manager QDESK not found; failure drills run only against the local lab')

def psql(sql):
    """Run one SQL statement in the lab Postgres container and return trimmed text output."""
    r=subprocess.run(['docker','exec','qdesk-postgres','psql','-U','queuedesk','-d','queuedesk','-tA','-c',sql],
        capture_output=True,text=True,timeout=30)
    assert r.returncode==0,(r.returncode,r.stderr)
    return r.stdout.strip()

def runmqsc(script):
    """Pipe an MQSC script into the lab queue manager; returns the completed process."""
    r=subprocess.run(['docker','exec','-i','qdesk-mq','bash','-c','runmqsc QDESK'],input=script,
        capture_output=True,text=True,timeout=60)
    return r

def wait_attempt(iid, result, timeout=60):
    """Poll until a processing-attempt row with the given result exists (or time out)."""
    deadline=time.time()+timeout
    while time.time()<deadline:
        if psql(f"SELECT COUNT(*) FROM processing_attempt WHERE instruction_id='{iid}' AND result='{result}'")>='1':
            return
        time.sleep(1)
    raise AssertionError(f'no {result} attempt for {iid}')

def wait_published(runid, total, timeout=30):
    """Poll until the run's outbox has fully drained to the expected published total."""
    deadline=time.time()+timeout
    while time.time()<deadline:
        summary=run_summary(runid)
        if summary['outboxPublished']==total and summary['outboxPending']==0: return summary
        time.sleep(1)
    raise AssertionError(f"outbox did not drain for {runid}: {run_summary(runid)}")

def load_report(path):
    """Load a generator report and assert the shared report contract (keys present, no failures)."""
    with open(path) as f: report=json.load(f)
    for key in ['scenario','seed','runId','attempted','unique','outcomes','httpLatencyMs','endToEndLatencyMs','unresolved','assertionFailures']:
        assert key in report,(key,path)
    assert report['assertionFailures']==[],report['assertionFailures']
    return report

with tempfile.TemporaryFile() as log:
    for port,helper in [(5442,'./scripts/postgres up'),(1414,'./scripts/mq up')]:
        try:
            with socket.create_connection(('127.0.0.1',port),timeout=2): pass
        except OSError:
            raise SystemExit(f'Port {port} is not reachable; start it with {helper}')
    tag=f"{os.getpid()}-{int(time.time())}"
    reports=root/'reports'
    process=start(log)
    try:
        # SC01 normal: 20 distinct admissions reach COMPLETED_SIMULATED, none unresolved.
        out=generate('--send','--observe','--scenario','normal','--seed','42','--count','20','--rate','10',
            '--run-id',f'sc01-{tag}','--base-url',base,'--report',str(reports/f'sc01-{tag}.json'))
        assert 'unknown=0 failed=0 unsent=0' in out, out
        rep=load_report(reports/f'sc01-{tag}.json')
        assert rep['attempted']==20 and rep['unique']==20 and rep['unresolved']==[],rep
        assert rep['outcomes']=={'COMPLETED_SIMULATED':20,'REJECTED':0},rep['outcomes']
        summary=wait_published(f'sc01-{tag}',20)
        assert summary['admitted']==20 and summary['byState']=={'COMPLETED_SIMULATED':20},summary
        print('SC01 normal: 20/20 COMPLETED_SIMULATED, 20/20 events published')
        # SC02 duplicates: 25 attempts, 20 rows, 20 events, repeats map to originals.
        out=generate('--send','--observe','--scenario','duplicate','--seed','42','--count','20','--rate','10',
            '--run-id',f'sc02-{tag}','--base-url',base,'--report',str(reports/f'sc02-{tag}.json'))
        assert 'attempted=25 accepted=20 repeated=5' in out, out
        rep=load_report(reports/f'sc02-{tag}.json')
        assert rep['unique']==20 and rep['unresolved']==[],rep
        summary=wait_published(f'sc02-{tag}',20)
        assert summary['admitted']==20 and summary['byState']=={'COMPLETED_SIMULATED':20},summary
        print('SC02 duplicates: 25 attempts, 20 rows, 20 events, original IDs preserved')
        # SC03 conflict: 409s change nothing; originals complete.
        out=generate('--send','--observe','--scenario','conflict','--seed','42','--count','20','--rate','10',
            '--run-id',f'sc03-{tag}','--base-url',base,'--report',str(reports/f'sc03-{tag}.json'))
        assert 'unknown=0 failed=0 unsent=0' in out, out
        summary=wait_published(f'sc03-{tag}',10)
        assert summary['admitted']==10 and summary['byState']=={'COMPLETED_SIMULATED':10},summary
        print('SC03 conflict: 10x(202+409), originals unchanged and completed')
        # SC04 invalid: 400 each, zero rows.
        out=generate('--send','--observe','--scenario','invalid','--seed','42','--count','20','--rate','10',
            '--run-id',f'sc04-{tag}','--base-url',base,'--report',str(reports/f'sc04-{tag}.json'))
        assert 'invalid=20' in out, out
        summary=run_summary(f'sc04-{tag}')
        assert summary['admitted']==0 and summary['outboxPending']==0 and summary['outboxPublished']==0,summary
        print('SC04 invalid: 20x400, zero instruction/outbox rows')
        # SC05 burst: 100 at capped rate/concurrency, all accounted and drained.
        out=generate('--send','--observe','--scenario','burst','--seed','42','--count','100','--rate','10',
            '--concurrency','4','--run-id',f'sc05-{tag}','--base-url',base,'--report',str(reports/f'sc05-{tag}.json'))
        assert 'unknown=0 failed=0 unsent=0' in out, out
        rep=load_report(reports/f'sc05-{tag}.json')
        assert rep['attempted']==100 and rep['unresolved']==[],rep
        assert rep['outcomes']=={'COMPLETED_SIMULATED':100,'REJECTED':0},rep['outcomes']
        assert rep['endToEndLatencyMs']['count']==100,rep['endToEndLatencyMs']
        summary=wait_published(f'sc05-{tag}',100)
        assert summary['admitted']==100,summary
        print('SC05 burst: 100/100 accounted, drained, latencies captured')
        # SC11 rejection: definitive REJECTED with reason, acknowledged normally.
        out=generate('--send','--observe','--scenario','reject','--seed','42','--count','20','--rate','10',
            '--run-id',f'sc11-{tag}','--base-url',base,'--report',str(reports/f'sc11-{tag}.json'))
        assert 'unknown=0 failed=0 unsent=0' in out, out
        rep=load_report(reports/f'sc11-{tag}.json')
        assert rep['outcomes']=={'COMPLETED_SIMULATED':0,'REJECTED':20},rep['outcomes']
        summary=wait_published(f'sc11-{tag}',20)
        assert summary['admitted']==20 and summary['byState']=={'REJECTED':20},summary
        print('SC11 rejection: 20/20 REJECTED with reason, no transport retry')
        # SC09a lost replies: pending first, then reconciled from receiver evidence.
        out=generate('--send','--observe','--scenario','uncertain','--seed','42','--count','20','--rate','10',
            '--run-id',f'sc09a-{tag}','--base-url',base,'--report',str(reports/f'sc09a-{tag}.json'))
        assert 'unknown=0 failed=0 unsent=0' in out, out
        rep=load_report(reports/f'sc09a-{tag}.json')
        assert rep['outcomes']=={'COMPLETED_SIMULATED':15,'REJECTED':5},rep['outcomes']
        assert rep['unresolved']==[],rep
        summary=wait_published(f'sc09a-{tag}',20)
        assert summary['admitted']==20,summary
        assert summary['byState']=={'COMPLETED_SIMULATED':15,'REJECTED':5},summary
        assert summary['reconcileOpen']==0,summary
        print('SC09a lost replies: 20/20 reconciled (15 completed, 5 rejected), no open tasks')
        # SC09b unknown: stays pending with open tasks; no rejection, no replacement.
        out=generate('--send','--observe','--observe-seconds','25','--scenario','unknown','--seed','42','--count','5','--rate','10',
            '--run-id',f'sc09b-{tag}','--base-url',base,'--report',str(reports/f'sc09b-{tag}.json'),expected=2)
        rep=load_report(reports/f'sc09b-{tag}.json')
        assert rep['attempted']==5 and len(rep['unresolved'])==5,rep
        summary=run_summary(f'sc09b-{tag}')
        assert summary['admitted']==5,summary
        assert summary['byState']=={'PENDING_CONFIRMATION':5},summary
        assert summary['reconcileOpen']==5,summary
        print('SC09b unknown: 5/5 honestly pending, 5 open tasks, exit 2')
        # SC12 deadline: sending stops, report lists the unsent, exit 2, no fabrication.
        out=generate('--send','--observe','--scenario','normal','--seed','42','--count','20','--rate','2',
            '--send-seconds','3','--run-id',f'sc12-{tag}','--base-url',base,
            '--report',str(reports/f'sc12-{tag}.json'),expected=2)
        rep=load_report(reports/f'sc12-{tag}.json')
        assert rep['unsent']>0 and len(rep['unresolved'])==rep['unsent'],rep
        assert rep['attempted']+rep['unsent']==20,rep
        print(f"SC12 deadline: {rep['attempted']} sent, {rep['unsent']} honestly unsent, exit 2")
        # SC06 MQ outage: 202s stay RECEIVED-honest while the broker is down;
        # pending outbox survives the broker restart and drains with stable IDs.
        lab_guard()
        subprocess.run([str(root/'scripts/mq'),'down'],cwd=root,check=True,capture_output=True,timeout=60)
        try:
            out=generate('--send','--scenario','normal','--seed','42','--count','20','--rate','10',
                '--run-id',f'sc06-{tag}','--base-url',base,'--report',str(reports/f'sc06-{tag}.json'))
            assert 'accepted=20' in out and 'unknown=0 failed=0 unsent=0' in out, out
            summary=run_summary(f'sc06-{tag}')
            assert summary['admitted']==20 and summary['outboxPending']==20 and summary['outboxPublished']==0,summary
            assert summary['byState']=={'RECEIVED':20},summary
        finally:
            subprocess.run([str(root/'scripts/mq'),'up'],cwd=root,check=True,capture_output=True,timeout=300)
        ids_before=sorted(psql(f"SELECT instruction_id FROM instruction WHERE run_id='sc06-{tag}' ORDER BY 1").split())
        assert len(ids_before)==20,ids_before
        summary=wait_published(f'sc06-{tag}',20,timeout=180)
        assert summary['byState']=={'COMPLETED_SIMULATED':20},summary
        ids_after=sorted(psql(f"SELECT instruction_id FROM instruction WHERE run_id='sc06-{tag}' ORDER BY 1").split())
        assert ids_before==ids_after,'IDs changed across the outage'
        print('SC06 MQ outage: 20x202 while down, pending survived restart, drained with stable IDs')
        # SC07 controlled redelivery: the same eventId twice, one durable
        # result, visible dedup, receiver called once.
        lab_guard()
        out=generate('--send','--observe','--scenario','normal','--seed','7','--count','1','--rate','10',
            '--run-id',f'sc07-{tag}','--base-url',base,'--report',str(reports/f'sc07-{tag}.json'))
        iid=psql(f"SELECT instruction_id FROM instruction WHERE run_id='sc07-{tag}'")
        eid=psql(f"SELECT event_id FROM processing_attempt WHERE instruction_id='{iid}' AND result='SUCCESS'")
        audit_before=psql(f"SELECT COUNT(*) FROM instruction_audit WHERE instruction_id='{iid}'")
        r=subprocess.run([str(root/'scripts/redeliver'),base,iid,eid],cwd=root,capture_output=True,text=True,timeout=60)
        assert r.returncode==0,(r.returncode,r.stdout,r.stderr)
        wait_attempt(iid,'DUPLICATE_SKIPPED',timeout=60)
        status,view=request('/api/v1/instructions/'+iid)
        assert status==200 and view['status']=='COMPLETED_SIMULATED',(status,view)
        assert psql(f"SELECT COUNT(*) FROM instruction_audit WHERE instruction_id='{iid}'")==audit_before
        assert psql(f"SELECT COUNT(*) FROM processing_attempt WHERE instruction_id='{iid}' AND result='SUCCESS'")=='1'
        assert psql(f"SELECT calls FROM receiver_result WHERE instruction_id='{iid}'")=='1'
        print('SC07 redelivery: same eventId twice, one result, dedup visible, receiver called once')
        # SC08 worker crash between commits: halt after the DB commit, restart,
        # prove the result survived and redelivery absorbs as a duplicate.
        lab_guard()
        stop(process)
        process=start(log,{'QUEUEDESK_FAULT_LAB_ARMED':'1','QUEUEDESK_FAULT_WORKER_HALT_AFTER_COMMIT':'1'})
        out=generate('--send','--scenario','normal','--seed','8','--count','1','--rate','10',
            '--run-id',f'sc08-{tag}','--base-url',base,'--report',str(reports/f'sc08-{tag}.json'))
        assert 'accepted=1' in out, out
        try:
            process.wait(timeout=90)
        except subprocess.TimeoutExpired:
            raise AssertionError('fault latch did not trip; app still alive')
        assert process.returncode==99,process.returncode
        iid=psql(f"SELECT instruction_id FROM instruction WHERE run_id='sc08-{tag}'")
        assert psql(f"SELECT state FROM instruction WHERE instruction_id='{iid}'")=='COMPLETED_SIMULATED'
        assert psql(f"SELECT COUNT(*) FROM processed_message WHERE instruction_id='{iid}'")=='1'
        process=start(log)
        wait_attempt(iid,'DUPLICATE_SKIPPED',timeout=90)
        status,view=request('/api/v1/instructions/'+iid)
        assert status==200 and view['status']=='COMPLETED_SIMULATED',(status,view)
        assert psql(f"SELECT COUNT(*) FROM processing_attempt WHERE instruction_id='{iid}' AND result='SUCCESS'")=='1'
        assert psql(f"SELECT calls FROM receiver_result WHERE instruction_id='{iid}'")=='1'
        assert psql(f"SELECT COUNT(*) FROM instruction_audit WHERE instruction_id='{iid}'")=='3'
        print('SC08 crash: halted 99 between commits, result survived, redelivery absorbed')
        # SC10a poison held: malformed event routes with reason, consumer lives.
        lab_guard()
        r=subprocess.run([str(root/'scripts/poison'),f'sc10a-{tag}','{not valid json'],cwd=root,capture_output=True,text=True,timeout=60)
        assert r.returncode==0,(r.returncode,r.stdout,r.stderr)
        deadline=time.time()+120
        held=None
        while time.time()<deadline:
            status,view=request('/api/v1/quarantine')
            assert status==200,(status,view)
            held=[i for i in view['items'] if i['reason']=='UNPARSEABLE_ENVELOPE']
            if held and not view['stale']: break
            time.sleep(2)
        assert held,f'poison never projected: {view}'
        out=generate('--send','--observe','--scenario','normal','--seed','10','--count','1','--rate','10',
            '--run-id',f'sc10a-alive-{tag}','--base-url',base,'--report',str(reports/f'sc10a-{tag}.json'))
        assert 'unknown=0 failed=0 unsent=0' in out, out
        print('SC10a poison: held with reason UNPARSEABLE_ENVELOPE, consumer alive')
        # SC10b broken route: no silent loss — halt with work retained, repair,
        # restart, route. Touches ONLY the lab backout queue on QDESK.
        lab_guard()
        for _ in range(5):
            runmqsc('CLEAR QL(QUEUEDESK.INSTRUCTION.BACKOUT)\nEND\n')
            depth=runmqsc('DISPLAY QL(QUEUEDESK.INSTRUCTION.BACKOUT) CURDEPTH\nEND\n').stdout
            if 'CURDEPTH(0)' in depth: break
            time.sleep(3)
        else: raise AssertionError(f'backout not drained: {depth}')
        r=runmqsc('DELETE QL(QUEUEDESK.INSTRUCTION.BACKOUT)\nEND\n')
        assert 'AMQ8007' in r.stdout,(r.stdout,r.stderr)
        r=subprocess.run([str(root/'scripts/poison'),f'sc10b-{tag}','{bad route drill'],cwd=root,capture_output=True,text=True,timeout=60)
        assert r.returncode==0,(r.returncode,r.stdout,r.stderr)
        deadline=time.time()+120
        while time.time()<deadline:
            if psql("SELECT COUNT(*) FROM processing_attempt WHERE result='ROUTE_FAILED'")>='1': break
            time.sleep(2)
        else: raise AssertionError('route failure never recorded')
        depth=runmqsc('DISPLAY QL(QUEUEDESK.INSTRUCTION.IN) CURDEPTH\nEND\n').stdout
        assert 'CURDEPTH(1)' in depth,depth
        deadline=time.time()+30
        while time.time()<deadline:
            log.flush(); log.seek(0)
            if 'worker.stopped' in log.read().decode(errors='replace'): break
            time.sleep(1)
        else: raise AssertionError('halt not logged')
        runmqsc(open(root/'scripts/mq-setup.mqsc').read())
        depth=runmqsc('DISPLAY QL(QUEUEDESK.INSTRUCTION.BACKOUT) CURDEPTH\nEND\n').stdout
        assert 'CURDEPTH(' in depth and 'AMQ8043' not in depth,depth
        stop(process)
        repair_cutoff=datetime.now(timezone.utc)
        process=start(log)
        deadline=time.time()+180
        held2=None
        # The SC10a projection was correctly resolved when this drill CLEARed
        # the backout queue, so "two open rows" is the wrong expectation now:
        # assert the SC10b poison itself — a fresh UNPARSEABLE observation
        # first seen after the repair — plus both poisons in history.
        while time.time()<deadline:
            status,view=request('/api/v1/quarantine')
            if status==200 and not view['stale']:
                fresh=[i for i in view['items'] if i['reason']=='UNPARSEABLE_ENVELOPE'
                       and datetime.fromisoformat(i['firstSeenAt'].replace('Z','+00:00'))>repair_cutoff]
                if fresh: held2=True; break
            time.sleep(2)
        assert held2,f'second poison never routed after repair: {view}'
        assert psql("SELECT COUNT(*) FROM quarantine_record WHERE reason='UNPARSEABLE_ENVELOPE'")>='2'
        out=generate('--send','--observe','--scenario','normal','--seed','11','--count','1','--rate','10',
            '--run-id',f'sc10b-alive-{tag}','--base-url',base,'--report',str(reports/f'sc10b-{tag}.json'))
        assert 'unknown=0 failed=0 unsent=0' in out, out
        with open(reports/f'sc10-{tag}.json','w') as f:
            json.dump({'scenario':'poison-and-broken-route','runTag':tag,'heldWithReason':'UNPARSEABLE_ENVELOPE',
                'routeFailedRecorded':True,'retainedOnInput':True,'haltLogged':True,'recoveredAfterRepair':True,
                'consumerAliveAfter':True},f,indent=2)
        print('SC10b broken route: retained + halted, repaired, routed, consumer alive')
    finally: stop(process)
print('PASS: SC01-SC10 (SC09a/SC09b), SC11-SC12 with stored reports under reports/.')
