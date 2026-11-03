"""CORE-001/002/ORG-001：只创建可销毁的临时库，不接受已有数据库作为输入。"""
from pathlib import Path
import os,sys,subprocess,secrets,json,uuid,shutil,time
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools/verification/core-stack'))
from process_tree import stop_tree
RUN=uuid.uuid4().hex[:12];OUT=ROOT/'.local/engineering'/RUN;OUT.mkdir(parents=True)
env=dict(os.environ)
java=env.get('JAVA_HOME','')
if not (Path(java)/'bin/java.exe' if os.name=='nt' else Path(java)/'bin/java').exists():raise SystemExit('请先配置 JDK 25 的 JAVA_HOME')
env['MAVEN_USER_HOME']=str(ROOT/'.local/maven')
for name in ['ADMIN','APP','IDENTITY','SESSION','AUDIT']:env['TS_'+name+'_PASSWORD']=secrets.token_urlsafe(30)
private=[v for k,v in env.items() if k.startswith('TS_') and k.endswith('_PASSWORD')]
def safe(s):
 for v in private:s=s.replace(v,'[REDACTED]')
 return s
docker=shutil.which('docker') or str(Path(env.get('LOCALAPPDATA',''))/'Programs/DockerDesktop/resources/bin/docker.exe')
def capture(args):
 p=subprocess.run(args,env=env,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=60)
 if p.returncode:raise RuntimeError(safe(p.stderr[-2000:]))
 return p.stdout.strip()
def step(label,args,cwd=ROOT/'backend'):
 print('['+label+']',flush=True)
 import threading
 with (OUT/(label+'.log')).open('w',encoding='utf-8') as log:
  p=subprocess.Popen(args,cwd=cwd,env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace',start_new_session=os.name!='nt')
  timer=threading.Timer(900,stop_tree,args=(p,));timer.start()
  try:
   for line in p.stdout:
    line=safe(line);log.write(line)
    if any(x in line for x in ['BUILD SUCCESS','BUILD FAILURE','[ERROR]','Tests run:','built in']):print(line.strip(),flush=True)
   code=p.wait()
  finally:timer.cancel();stop_tree(p)
 if code:raise RuntimeError(label+' failed; log: '+str(OUT/(label+'.log')))
mvn=[str(ROOT/'backend'/('mvnw.cmd' if os.name=='nt' else 'mvnw')),'-B','-ntp','-s',str(ROOT/'backend/.mvn/settings.xml'),'-gs',str(ROOT/'backend/.mvn/settings.xml'),'-Dmaven.repo.local='+str(ROOT/'.local/maven/repository')]
container=None;report={'run_id':RUN,'status':'running','task':'CORE-001/CORE-002/ORG-001/ORG-002'}
try:
 report['java']=capture([str(Path(java)/'bin'/('java.exe' if os.name=='nt' else 'java')),'--version'])
 if '25.' not in report['java'].splitlines()[0]:raise RuntimeError('正式工程要求 JDK 25')
 context=capture([docker,'context','show']);env['DOCKER_HOST']=capture([docker,'context','inspect',context,'--format','{{.Endpoints.docker.Host}}'])
 image='postgres@sha256:979bccfde35445db618c65107d27d08c6c21339d8dc3ad4c830c6633947be0e4';env['TS_POSTGRES_IMAGE']=image
 env['POSTGRES_PASSWORD']=env['TS_ADMIN_PASSWORD'];env['POSTGRES_DB']='teraschool_test'
 container=capture([docker,'run','-d','--rm','--name','teraschool-engineering-'+RUN,'--label','org.teraschool.engineering='+RUN,'-e','POSTGRES_PASSWORD','-e','POSTGRES_DB','-p','127.0.0.1::5432',image,'-c','max_connections=300'])
 details=json.loads(capture([docker,'inspect',container]))[0];port=details['NetworkSettings']['Ports']['5432/tcp'][0]['HostPort'];env['TS_JDBC_URL']='jdbc:postgresql://127.0.0.1:'+port+'/teraschool_test'
 for attempt in range(40):
  try:capture([docker,'exec',container,'pg_isready','-U','postgres']);break
  except RuntimeError:time.sleep(1)
 else:raise RuntimeError('PG 未就绪')
 # SQL 通过 stdin 传递，不在命令行或日志中输出随机口令。
 roles='\n'.join("CREATE ROLE ts_"+kind+" LOGIN PASSWORD '"+env['TS_'+kind.upper()+'_PASSWORD']+"' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;" for kind in ['app','identity','session','audit'])
 p=subprocess.run([docker,'exec','-i',container,'psql','-v','ON_ERROR_STOP=1','-U','postgres','-d','teraschool_test'],input=roles,text=True,capture_output=True,env=env)
 if p.returncode:raise RuntimeError(safe(p.stderr))
 step('extract',[sys.executable,str(ROOT/'tools/migration/extract_legacy.py'),'--self-check'],cwd=ROOT);step('clean',mvn+['clean']);step('migration',mvn+['-N','flyway:migrate']);step('codegen',mvn+['-N','jooq-codegen:generate']);step('format',mvn+['spotless:apply'] if '--format' in sys.argv else mvn+['spotless:check']);step('verify',mvn+['verify'])
 import xml.etree.ElementTree as ET
 suites=[ET.parse(p).getroot() for p in (ROOT/'backend/applications/server/target/surefire-reports').glob('TEST-*.xml')]
 if not suites or any(int(s.get('failures','0'))+int(s.get('errors','0'))+int(s.get('skipped','0')) for s in suites):raise RuntimeError('测试必须执行且不可失败/跳过')
 report['tests']=sum(int(s.get('tests','0')) for s in suites)
 pnpm=shutil.which('pnpm.cmd' if os.name=='nt' else 'pnpm')
 if not pnpm:raise RuntimeError('需要 pnpm 11.23.0')
 frontend=ROOT/'frontend'
 step('frontend-install',[pnpm,'install','--frozen-lockfile','--ignore-scripts','--strict-peer-dependencies','--store-dir',str(ROOT/'.local/pnpm-store')],frontend)
 for label in ['generate','typecheck','lint','format:check','test','build']:step('frontend-'+label.replace(':','-'),[pnpm,'run',label],frontend)
 report['status']='passed'
except BaseException as e:report['error']=safe(str(e));report['status']='failed';raise
finally:
 if container:
  try:capture([docker,'stop',container]);report['container_removed']=True
  except Exception:report['container_removed']=False
 (OUT/'result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8');print(str(OUT/'result.json'),flush=True)
