"""Sample actual container usage until a stop file appears; never invoke inference."""
import json,subprocess,time,sys
from datetime import datetime,timezone
from pathlib import Path
root=Path(__file__).resolve().parent
stop=root/(sys.argv[2] if len(sys.argv)>2 else 'stop-sampling')
with (root/(sys.argv[1] if len(sys.argv)>1 else 'resources.jsonl')).open('w') as out:
    while not stop.exists():
        result=subprocess.run(['docker','stats','--no-stream','--format','{{json .}}'],capture_output=True,text=True,timeout=20)
        rows=[]
        for line in result.stdout.splitlines():
            try:
                row=json.loads(line)
                if row.get('Name','').startswith('repaircalc'): rows.append(row)
            except json.JSONDecodeError: pass
        out.write(json.dumps({'time':datetime.now(timezone.utc).isoformat(),'containers':rows,'exitCode':result.returncode})+'\n')
        out.flush()
        time.sleep(5)
