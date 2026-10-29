import concurrent.futures, hashlib, json, pathlib, subprocess, time
manifest=pathlib.Path('/tmp/gemma4-12b-manifest.json').read_bytes()
m=json.loads(manifest)
root=pathlib.Path(subprocess.check_output(['docker','volume','inspect','repaircalc_ollama-data','--format','{{.Mountpoint}}'],text=True).strip())/'models'
blobs=root/'blobs'; blobs.mkdir(parents=True,exist_ok=True)
def fetch(layer):
 digest=layer['digest']; target=blobs/digest.replace(':','-'); part=target.with_suffix('.download')
 if not target.exists():
  print('Downloading',digest,layer['size'],flush=True)
  subprocess.run(['curl','--tls-max','1.2','--http1.1','--noproxy','*','-fsSL','--retry','5','--retry-delay','2','--connect-timeout','20','--speed-limit','1024','--speed-time','90','-C','-','https://registry.ollama.ai/v2/library/gemma4/blobs/'+digest,'-o',str(part)],check=True)
  assert part.stat().st_size==layer['size'],digest
  with part.open('rb') as f: actual=hashlib.file_digest(f,'sha256').hexdigest()
  assert actual==digest.split(':')[1],digest
  part.rename(target)
 print('Verified',digest,flush=True)
 return {'digest':digest,'bytes':layer['size'],'sha256Verified':True}
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
 results=list(pool.map(fetch,[m['config']]+m['layers']))
p=root/'manifests/registry.ollama.ai/library/gemma4/12b';p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(manifest)
pathlib.Path('reports/deployment/2026-10-29/server/model-download.json').write_text(json.dumps(results,indent=2))
print('MODEL_DOWNLOAD_COMPLETE',flush=True)
