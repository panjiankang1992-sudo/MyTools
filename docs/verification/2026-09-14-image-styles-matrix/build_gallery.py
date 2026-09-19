"""根据原始验收记录生成可筛选图册，不改变原图片内容。"""
import html
import json
from pathlib import Path

root=Path(__file__).resolve().parent
reviews=json.loads((root/'reviews.json').read_text())
run=json.loads((root/'results/report.json').read_text())
results={r['style']:r for r in run['results']}
labels={'PASS':'通过','LIMITED':'有限通过','FAIL':'未通过','PENDING':'待检查'}
cards=[]
for row in [{'id':'baseline','name':'无风格对照','criterion':'统一主体与参数','verdict':'PASS','notes':'红茶壶、草莓盘、木桌、窗与绿植完整，摄影效果。'},*reviews]:
 item=results.get(row['id'])
 if not item:continue
 name=html.escape(row['name']);file='results/'+row['id']+'.png'
 cards.append('<article data-verdict="'+row['verdict']+'"><a href="'+file+'" target="_blank"><img loading="lazy" src="'+file+'" alt="'+name+'"></a><div><h2>'+name+' <small>'+str(item['elapsedMillis']//1000)+'s</small></h2><b>'+labels[row['verdict']]+'</b><p>'+html.escape(row['notes'])+'</p><details><summary>检查要点</summary>'+html.escape(row['criterion'])+'</details></div></article>')
supplement_cards=[]
supplement_report=root/'results/supplements/report.json'
if supplement_report.exists():
 for item in json.loads(supplement_report.read_text()):
  name=item['name'];file='results/supplements/'+name+'.png'
  caption='无风格对照' if name.endswith('-baseline') else '应用风格'
  supplement_cards.append('<article><a href="'+file+'" target="_blank"><img loading="lazy" src="'+file+'" alt="'+html.escape(name)+'"></a><div><h2>'+caption+'</h2><p>'+html.escape(name)+'</p><p>'+html.escape(item['request']['prompt'])+'</p></div></article>')
page='''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>24 种图片风格逐项验收</title><style>body{font:16px/1.6 system-ui;margin:0;background:#f5f6f2;color:#24352e}header{max-width:1400px;margin:auto;padding:32px 24px}h1{margin:0}p{margin:8px 0}main{display:grid;grid-template-columns:repeat(auto-fit,minmax(300px,1fr));gap:20px;max-width:1400px;margin:auto;padding:0 24px 40px}article{background:white;border:1px solid #dbe0d8;border-radius:14px;overflow:hidden}img{width:100%;aspect-ratio:1;object-fit:contain;background:#eee;display:block}article>div{padding:18px}h2{font-size:20px;margin:0 0 8px}small{float:right;color:#68786d;font-size:14px}button{padding:8px 16px;margin:8px 8px 0 0;border:1px solid #94ab9c;background:white;border-radius:20px;cursor:pointer}button.active{background:#244d3a;color:white}b{color:#265c43}article[data-verdict=LIMITED] b{color:#98621a}article[data-verdict=FAIL] b{color:#ac3d37}[hidden]{display:none!important}</style><header><h1>24 种图片风格逐项验收</h1><p>同一主体 · Krea 2 Turbo · 1024×1024 · seed 932 · 风格 v1</p><p>首轮每种 1 张，不代表跨主体和种子的稳定性。点击图片查看原图。详细结论以验收报告为准。</p><nav><button class="active" data-filter="ALL">全部</button><button data-filter="PASS">通过</button><button data-filter="LIMITED">有限通过</button><button data-filter="FAIL">未通过</button></nav></header><main>'''+''.join(cards)+'''</main><header><h1>弱效果项补测</h1><p>每组相同主体、seed 933：先无风格，再应用风格。结论见报告，首轮证据完整保留。</p></header><main>'''+''.join(supplement_cards)+'''</main><script>for(const b of document.querySelectorAll('button'))b.onclick=()=>{document.querySelectorAll('button').forEach(x=>x.classList.toggle('active',x===b));document.querySelectorAll('article[data-verdict]').forEach(x=>x.hidden=b.dataset.filter!=='ALL'&&x.dataset.verdict!==b.dataset.filter)}</script></html>'''
(root/'gallery.html').write_text(page)
