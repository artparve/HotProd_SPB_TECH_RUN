import json,subprocess
from pathlib import Path
root=Path(__file__).resolve().parent
expected={'backend':(1,768),'postgres':(.5,512),'frontend':(.25,128),'estimator':(.5,256),'ollama':(8,12288)}
rows=[]
for service,(cpu,mib) in expected.items():
    name='repaircalc-'+service+'-1'
    fmt='{"name":{{json .Name}},"nanoCpus":{{.HostConfig.NanoCpus}},"memory":{{.HostConfig.Memory}},"memorySwap":{{.HostConfig.MemorySwap}},"oomKilled":{{.State.OOMKilled}},"restarts":{{.RestartCount}},"health":{{json .State.Health.Status}},"command":{{json .Config.Cmd}}}'
    row=json.loads(subprocess.check_output(['docker','inspect','--format',fmt,name],text=True))
    assert row['nanoCpus']==int(cpu*1e9),row
    assert row['memory']==mib*1024**2,row
    assert row['memorySwap']==row['memory'],row
    assert not row['oomKilled'] and row['health']=='healthy',row
    raw=subprocess.check_output(['docker','exec',name,'sh','-c','cat /sys/fs/cgroup/cpu.max /sys/fs/cgroup/memory.max /sys/fs/cgroup/memory.swap.max'],text=True).splitlines()
    row['cgroup']={'cpu.max':raw[0],'memory.max':raw[1],'memory.swap.max':raw[2]}
    assert int(raw[1])==row['memory'] and raw[2]=='0',row
    quota,period=map(int,raw[0].split());assert quota/period==cpu,row
    if service=='estimator': assert row['command'][row['command'].index('--num-threads')+1]=='8'
    events=subprocess.check_output(['docker','exec',name,'cat','/sys/fs/cgroup/memory.events'],text=True)
    row['cgroup']['memory.events']={k:int(v) for k,v in (line.split() for line in events.splitlines())}
    row['cgroup']['memory.peak']=int(subprocess.check_output(['docker','exec',name,'cat','/sys/fs/cgroup/memory.peak'],text=True))
    assert row['cgroup']['memory.events']['oom_kill']==0,row
    assert row['cgroup']['memory.events']['oom']==0,row
    rows.append(row)
(root/'effective-limits.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2)+'\n')
print('PASS: all 5 services healthy; Docker and cgroup CPU/RAM/swap limits agree; estimator requests 8 threads.')
