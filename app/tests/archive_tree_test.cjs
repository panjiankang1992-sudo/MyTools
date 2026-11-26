const assert = require('node:assert/strict');
const { ArchiveTree: tree } = require(process.argv[2]);
const entries = [
 {id:0,path:'docs/a.txt',size:4,directory:false,encrypted:false},
 {id:1,path:'docs/nested/a.txt',size:6,directory:false,encrypted:true},
 {id:2,path:'empty',size:0,directory:true,encrypted:false},
 {id:3,path:'readme.txt',size:2,directory:false,encrypted:false}
];
const rows = tree.build(entries);
assert.deepEqual(rows.map(x=>x.path),['docs','docs/a.txt','docs/nested','docs/nested/a.txt','empty','readme.txt']);
assert.deepEqual(rows[0].ids,[0,1]);
assert.equal(rows[0].size,10);
assert.deepEqual(tree.visible(rows,[],'').map(x=>x.path),['docs','empty','readme.txt']);
assert.deepEqual(tree.visible(rows,['docs'],'').map(x=>x.path),['docs','docs/a.txt','docs/nested','empty','readme.txt']);
assert.deepEqual(tree.visible(rows,[],'NESTED/a').map(x=>x.path),['docs','docs/nested','docs/nested/a.txt']);
assert.deepEqual(tree.select([3],rows[0],true),[3,0,1]);
assert.deepEqual(tree.select([3,0,1],rows[0],false),[3]);
assert.deepEqual(tree.select([0],rows[0],true),[0,1]);
assert.deepEqual(tree.exportNames(['docs/a.txt','other/a.txt']),['1-a.txt','2-a.txt']);
for(const p of ['../a','/a','a/../b','a//b','C:x','a\\b','a\0b']) assert.throws(()=>tree.safePath(p),/ARCHIVE_009/);
assert.equal(tree.safePath('empty/中文.txt'),'empty/中文.txt');
assert.ok(Buffer.byteLength(tree.exportNames(['中'.repeat(100)+'.txt'])[0])<240);
assert.equal(entries.length,4);
console.log('20 archive tree/export policy checks passed');
