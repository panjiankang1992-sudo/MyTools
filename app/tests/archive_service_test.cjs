// 使用真实临时文件和受控平台适配器，验证业务编排的失败边界。
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const ts = require('/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript');
const root = fs.mkdtempSync(path.join(os.tmpdir(),'mytools-archive-service-'));
const source = path.resolve(__dirname,'../entry/src/main/ets/features/tools/archive');
let count = 0;
const check = (condition, label) => { assert.ok(condition,label); count++; };
const io = {
 OpenMode: {READ_ONLY:fs.constants.O_RDONLY,WRITE_ONLY:fs.constants.O_WRONLY,CREATE:fs.constants.O_CREAT,TRUNC:fs.constants.O_TRUNC},
 accessSync:fs.existsSync, mkdirSync:(p,r=false)=>fs.mkdirSync(p,{recursive:r}),
 rmdirSync:p=>fs.rmSync(p,{recursive:true}), listFileSync:fs.readdirSync,
 statSync:fs.statSync, readTextSync:p=>fs.readFileSync(p,'utf8'), lstatSync:fs.lstatSync,
 openSync:(p,m)=>({fd:fs.openSync(p,m)}), closeSync:f=>fs.closeSync(f.fd),
 writeSync:(fd,s)=>fs.writeSync(fd,s), rename:fs.promises.rename,
 copyFile:async(a,b)=>{const data=fs.readFileSync(a);fs.writeSync(b,data);}
};
const modules = {};
let hashHook = async()=> 'fingerprint';
const engine = {run:async()=>{throw Error('Unexpected native call');}, status:()=>JSON.stringify({running:false,bytes:0,total:0})};
function load(name) {
 if(modules[name])return modules[name].exports;
 const module={exports:{}}; modules[name]=module;
 const code=ts.transpileModule(fs.readFileSync(path.join(source,name+'.ets'),'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020}}).outputText;
 const mockRequire = key => {
  if(key.startsWith('./'))return load(key.slice(2));
  if(key==='@ohos.file.hash')return {default:{hash:()=>hashHook()}};
  if(key==='@ohos.file.fs')return {default:io};
  if(key==='@ohos.file.fileuri')return {default:{FileUri:class{constructor(p){this.path=p;}}}};
  if(key==='@kit.ArkTS')return {util:{generateRandomUUID:crypto.randomUUID}};
  if(key==='libarchive_engine.so')return {default:engine};
  return {};
 };
 new Function('require','module','exports',code)(mockRequire,module,module.exports);
 return module.exports;
}
(async()=>{
 const {ArchiveJobService:Service}=load('ArchiveJobService');
 const service=new Service();
 const historyRoot=path.join(root,'results');fs.mkdirSync(historyRoot);service.historyRoot=historyRoot;
 const id=crypto.randomUUID();const job=path.join(historyRoot,id);fs.mkdirSync(job);
 fs.writeFileSync(path.join(job,'a'),'one');fs.writeFileSync(path.join(job,'b'),'second');
 const record={id,name:'synthetic.zip',createdAt:1,directories:['empty'],outputs:[{path:'nested/a.txt',source:'a',size:3},{path:'b.txt',source:'b',size:6}]};
 fs.writeFileSync(path.join(job,'record.json'),JSON.stringify(record));
 const out=path.join(root,'dest');fs.mkdirSync(out);
 for(const n of ['a','b'])fs.writeFileSync(path.join(out,n),'');
 const destinations=['a','b'].map(n=>path.join(out,n));
 let progress=[];
 await service.exportFiles(record,destinations,(done)=>progress.push(done));
 check(JSON.stringify(progress)==='[0,1,2]','all files reported exactly once');
 check(fs.readFileSync(destinations[1],'utf8')==='second','batch copied bytes');
 await assert.rejects(service.exportFiles(record,[destinations[0],destinations[0]],()=>{}),/ARCHIVE_008/);count++;
 fs.writeFileSync(destinations[1],'sentinel');progress=[];
 await assert.rejects(service.exportFiles(record,destinations,(done)=>{progress.push(done);if(done===1)service.cancel();}),/ARCHIVE_010/);count++;
 check(fs.readFileSync(destinations[1],'utf8')==='sentinel','cancel preserves pending destination');
 check(JSON.stringify(progress)==='[0,1]','cancel reports partial count');
 fs.writeFileSync(path.join(job,'b'),'changed-size');progress=[];
 await assert.rejects(service.exportFiles(record,destinations,done=>progress.push(done)),/ARCHIVE_004/);count++;
 check(fs.readFileSync(destinations[1],'utf8')==='sentinel','changed source rejected before truncating destination');
 await assert.rejects(service.exportDirectory(record,out,()=>{}),/ARCHIVE_004/);count++;
 check(fs.readdirSync(out).sort().join(',')==='a,b','failed folder rolled back without touching existing files');
 fs.writeFileSync(path.join(job,'b'),'second');
 const folder=await service.exportDirectory(record,out,()=>{});
 check(fs.readFileSync(path.join(out,folder,'nested/a.txt'),'utf8')==='one','folder hierarchy exported');
 check(fs.statSync(path.join(out,folder,'empty')).isDirectory(),'empty directory exported');
 // 有限候选轮询和不同条目密码由业务层真实运行，引擎接口仅注入确定的错误结果。
 service.workspace=path.join(root,'workspace');fs.mkdirSync(service.workspace);
 const passwords=[{id:'1',value:'first',createdAt:0,lastUsedAt:2,attempts:0,successes:1,fingerprint:''},{id:'2',value:'second',createdAt:0,lastUsedAt:1,attempts:0,successes:0,fingerprint:''}];
 const attempts=[];
 service.passwords.load=async()=>passwords.map(p=>({...p}));
 service.passwords.record=async(p,success)=>{attempts.push([p.value,success]);};
 const entries=[{id:0,path:'first.txt',size:1,directory:false,encrypted:true},{id:1,path:'second.txt',size:1,directory:false,encrypted:true}];
 engine.run=async(id,source,output,password,extract,ids)=>{
  const entry=entries[ids[0]];const valid=password===(entry.id===0?'first':'second');
  if(valid){fs.mkdirSync(output);fs.writeFileSync(path.join(output,entry.path),'x');}
  return JSON.stringify({code:valid?'':(password?'ARCHIVE_003':'ARCHIVE_002'),entries:valid?[entry]:[],format:'ZIP',bytes:valid?1:0,passwordUsed:!!password});
 };
 const mixed=await service.extract(entries,'',true,'mixed.zip');
 check(mixed.outputs.length===2,'mixed passwords complete both entries');
 check(attempts.some(a=>a[0]==='first'&&!a[1])&&attempts.some(a=>a[0]==='second'&&a[1]),'wrong and successful attempts recorded');
 check(attempts.length<=4,'candidate attempts remain finite');
 const cancelledImport=new Service();cancelledImport.context={cacheDir:root};
 const input=path.join(root,'input.zip');fs.writeFileSync(input,'sample');
 hashHook=async()=>{cancelledImport.cancel();return 'completed-hash';};
 await assert.rejects(cancelledImport.prepare(input),/ARCHIVE_010/);count++;
 check(!fs.readdirSync(root).some(n=>n.startsWith('archive-job-')),'cancel during hash cleans import workspace');
 console.log(JSON.stringify({passed:count,platform:'host adapters; not device API acceptance',temporary_files_removed_on_exit:true}));
})().catch(e=>{console.error(e);process.exitCode=1;}).finally(()=>fs.rmSync(root,{recursive:true,force:true}));
