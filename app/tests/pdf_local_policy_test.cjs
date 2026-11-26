const assert = require('node:assert/strict');
const {PdfLocalPolicy:P}=require(process.argv[2]);
assert.deepEqual(P.pages('1,3-4,3',4),[0,2,3]);
for(const value of ['0','5','2-1','1,','1.5','1-x'])assert.throws(()=>P.pages(value,4));
assert.deepEqual(P.pieces(2,[0,1],false),[{page:0,side:0},{page:0,side:1},{page:1,side:0},{page:1,side:1}]);
assert.deepEqual(P.pieces(2,[0],false),[{page:0,side:0},{page:0,side:1},{page:1,side:-1}]);
assert.deepEqual(P.pieces(2,[0,1],true),[{page:0,side:0},{page:1,side:0},{page:0,side:1},{page:1,side:1}]);
// 独立视觉坐标变换检查切分后矩形的角点范围。
const source={left:20,right:620,bottom:40,top:440};
function visual(x,y,rot){const u=(x-20)/600,v=(y-40)/400;return rot===0?[u,1-v]:rot===90?[v,u]:rot===180?[1-u,v]:[1-v,1-u];}
for(const rotation of [0,90,180,270])for(const vertical of [true,false])for(const ratio of [.25,.5,.75])for(const side of [0,1]){
 const b=P.cut(source,rotation,vertical,ratio,side);
 const q=[[b.left,b.bottom],[b.right,b.top],[b.left,b.top],[b.right,b.bottom]].map(([x,y])=>visual(x,y,rotation)[vertical?0:1]);
 assert.ok(Math.abs(Math.min(...q)-(side===0?0:ratio))<1e-9);
 assert.ok(Math.abs(Math.max(...q)-(side===0?ratio:1))<1e-9);
}
assert.equal(P.outputName('../escape.pdf','txt'),'.._escape.txt');
console.log('PASS ranges, mapping, 48 rotated crop cases, safe output names');
