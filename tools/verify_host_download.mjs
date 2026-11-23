import vm from 'node:vm';
import fs from 'node:fs/promises';
import path from 'node:path';
import {webcrypto} from 'node:crypto';
import assert from 'node:assert/strict';
const directory=path.resolve(process.argv[2]);
const script=await fs.readFile(path.join(directory,'download.js'),'utf8');
async function scenario(fail=false){
  const elements=new Map();let clicked=0;let blob;
  const element=id=>{if(!elements.has(id))elements.set(id,{textContent:'',hidden:true,value:0,disabled:false,addEventListener(event,fn){this.listener=fn;},click(){clicked++;}});return elements.get(id);};
  const context={document:{querySelector:element},window:{addEventListener(){}},Blob,crypto:webcrypto,URL:{createObjectURL(value){blob=value;return 'blob:test';},revokeObjectURL(){}},Uint8Array,console,
    fetch:async resource=>{
      if(fail && resource.includes('downloads/'))return new Response('Unavailable',{status:503});
      const bytes=await fs.readFile(path.join(directory,resource));
      return new Response(bytes,{status:200});
    }};
  vm.runInNewContext(script,context);
  await element('#download').listener();
  if(fail){assert.equal(clicked,0);assert.equal(element('#button-label').textContent,'Retry download');assert.equal(element('#download').disabled,false);}
  else{const release=JSON.parse(await fs.readFile(path.join(directory,'release.json'),'utf8'));assert.equal(blob.size,release.bytes);assert.equal(clicked,1);assert.equal(element('#save-file').download,release.filename);assert.match(element('#download-status').textContent,/APK verified/);}
}
await scenario();await scenario(true);
console.log('PASS: real download script reconstructs and verifies APK, starts save, and supports retry after a failed part.');
