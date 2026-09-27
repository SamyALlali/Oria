#!/usr/bin/env python3
"""Compare all historic RGB JSON outputs against the pre-fusion commit on seeded sessions.

Compiles the reference in a temporary directory; does not change the checkout or use Gradle.
"""
import argparse
import importlib.util, json, pathlib, random, subprocess, tempfile
repo=pathlib.Path(__file__).resolve().parents[2]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--baseline', default='b650dad8e96ef7a9b3ca8bd26c8f04471dedf2db')
parser.add_argument('--output', type=pathlib.Path, default=pathlib.Path(tempfile.gettempdir())/'oria-fusion-rgb-parity.json')
args=parser.parse_args()
baseline_ref=subprocess.check_output(['git','rev-parse',args.baseline],cwd=repo,text=True).strip()
modulepath=repo/'oria-htc/oria-lab-policy/run_policy.py'
spec=importlib.util.spec_from_file_location('fusion_policy_builder',modulepath)
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
current_command=module.prepare()
core='oria-htc/android-project/app/src/main/java/com/htc/vive/eagle/hackathon/starter/oria/core'
def run(command, rows):
 p=subprocess.run(command,input=''.join(json.dumps(row)+'\n' for row in rows),text=True,capture_output=True,check=True)
 return [json.loads(line) for line in p.stdout.splitlines()]
def project(candidate, baseline):
 if isinstance(baseline,dict):
  assert isinstance(candidate,dict) and baseline.keys()<=candidate.keys()
  return {key:project(candidate[key],value) for key,value in baseline.items()}
 if isinstance(baseline,list):
  assert isinstance(candidate,list) and len(candidate)==len(baseline)
  return [project(a,b) for a,b in zip(candidate,baseline)]
 return candidate
with tempfile.TemporaryDirectory(prefix='oria-baseline-rgb-') as directory:
 root=pathlib.Path(directory)
 paths=subprocess.check_output(['git','ls-tree','-r','--name-only',baseline_ref,'--',core],cwd=repo,text=True).splitlines()
 for path in paths+['oria-htc/oria-lab-policy/PolicyReplay.kt']:
  dest=root/path;dest.parent.mkdir(parents=True,exist_ok=True)
  dest.write_bytes(subprocess.check_output(['git','show',baseline_ref+':'+path],cwd=repo))
 module.ROOT=root/'oria-htc';module.HERE=root/'oria-htc/oria-lab-policy'
 baseline_command=module.prepare()
 results=[]
 for mode in ['LEGACY_IOU','STABLE_RGB_V2']:
  process=subprocess.Popen(baseline_command,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
  rows=[];baseline=[]
  def offer(row):
   rows.append(row);process.stdin.write(json.dumps(row)+'\n');process.stdin.flush()
   value=json.loads(process.stdout.readline());baseline.append(value);return value
  rng=random.Random(51927)
  alert_count=0;callback_count=0
  for session in [11,24,38]:
   offset=(session-11)*100_000
   start={'type':'start','sessionId':session,'atMs':offset}
   if session==11:start['config']={'trackingMode':mode}
   offer(start)
   for i in range(1,301):
    observed=offset+i*333
    detections=[]
    for j in range(rng.randrange(5)):
     left=[.03,.35,.7][j%3]+rng.uniform(-.01,.01)
     detections.append({'classId':rng.choice([0,0,1,2,3,4,5]),'confidence':rng.choice([.75,.84,.9,.97]),
       'box':{'left':left,'right':min(.99,left+.2),'top':.2,'bottom':.91}})
    if i%9 in [1,2,3]:
     detections=[{'classId':0,'confidence':.97,'box':{'left':.35,'right':.6,'top':.2,'bottom':.95}}]
    at=observed+(501 if i%37==0 else 25)
    response=offer({'type':'frame','sessionId':session,'frameId':i,'observedAtMs':observed,'nowMs':at,'detections':detections})
    alert=response.get('eligibleAlert')
    if alert:
     alert_count+=1
     reserved=offer({'type':'submitted','alertId':alert['id'],'nowMs':at})
     if reserved.get('accepted'):
      callback_count+=1
      offer({'type':'failed' if callback_count%5==0 else 'confirmed','ticketId':reserved['ticketId'],'nowMs':at+10})
   offer({'type':'stop','atMs':offset+100_300})
   offer({'type':'confirmed','ticketId':1,'nowMs':offset+100_400})
   offer({'type':'current','nowMs':offset+100_500})
  process.stdin.close();assert process.wait()==0,process.stderr.read()
  current=run(current_command,rows)
  assert len(current)==len(baseline)
  assert [project(a,b) for a,b in zip(current,baseline)]==baseline
  results.append({'mode':mode,'commands':len(rows),'frames':900,'eligible_alerts':alert_count,'callbacks':callback_count,'historical_fields_identical':True})
 report={'status':'PASS','baseline':baseline_ref,'scope':'Seeded synthetic sessions; exact comparison of every legacy JSON field, excluding only new diagnostic fields; no material/audio validation','results':results}
 args.output.parent.mkdir(parents=True,exist_ok=True)
 args.output.write_text(json.dumps(report,indent=2)+'\n')
 print(json.dumps(report))
