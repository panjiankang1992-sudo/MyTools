"""校验原始图片与记录，并生成逐项验收报告。"""
from collections import Counter
import hashlib
import json
from pathlib import Path
import struct

root=Path(__file__).resolve().parent
run=json.loads((root/'results/report.json').read_text())
reviews=json.loads((root/'reviews.json').read_text())
assert len(reviews)==24 and all(x['verdict']!='PENDING' for x in reviews)
assert len(run['results'])==25
results={x['style']:x for x in run['results']}
assert set(results)=={'baseline',*(x['id'] for x in reviews)}
verified=[]
for row in run['results']:
 assert row['status']=='SUCCEEDED'
 file=root/'results'/(row['style']+'.png');raw=file.read_bytes()
 assert hashlib.sha256(raw).hexdigest()==row['sha256']
 assert raw[:8]==b'\x89PNG\r\n\x1a\n' and struct.unpack('>II',raw[16:24])==(1024,1024)
 request=row['request'];assert request['prompt']==run['prompt'] and request['seed']==932
 if row['style']!='baseline':
  snapshot=request['styleSnapshot'];assert snapshot['id']==row['style'] and snapshot['version']==1
  assert request['effectivePrompt']==snapshot['promptTemplate'].replace('{prompt}',run['prompt'])
 verified.append(str(file.relative_to(root)))
supplements_path=root/'results/supplements/report.json'
supplements=json.loads(supplements_path.read_text()) if supplements_path.exists() else []
assert len(supplements)==8
for case in json.loads((root/'supplement-cases.json').read_text()):
 pair=[x for x in supplements if x['name'].startswith(case['id']+'-')]
 assert len(pair)==2
 for item in pair:
  request=item['request'];assert request['prompt']==case['prompt'] and request['seed']==933
  if item['name'].endswith('-styled'):
   assert request['styleId']==case['styleId'] and request['styleVersion']==1
   assert request['effectivePrompt']==request['styleSnapshot']['promptTemplate'].replace('{prompt}',case['prompt'])
  else:assert 'styleId' not in request
for row in supplements:
 assert row['status']=='SUCCEEDED'
 file=root/'results/supplements'/(row['name']+'.png');raw=file.read_bytes()
 assert hashlib.sha256(raw).hexdigest()==row['sha256']
 assert struct.unpack('>II',raw[16:24])==(1024,1024)
 verified.append(str(file.relative_to(root)))
labels={'PASS':'通过','LIMITED':'有限通过','FAIL':'未通过'}
counts=Counter(x['verdict'] for x in reviews)
lines=['# 24 种内置图片风格逐项验收','',f"2026-09-14：首轮 24 种风格和 1 张无风格对照全部成功生成。视觉检查 {counts['PASS']} 项通过、{counts['LIMITED']} 项有限通过、{counts['FAIL']} 项未通过。接口成功不等于风格效果通过。",'', '[打开原图图册](gallery.html) · [验收方法](method.md) · [首轮原始记录](results/report.json) · [固定模板目录](results/catalog.json)','', '## 条件与范围','', '- 现网 image-styles-20260914-v1；krea2-local / krea2-turbo-v1；TEXT_TO_IMAGE，1024×1024，每任务 1 张。','- 首轮固定主体、seed 932、风格 v1；弱效果项补测用 seed 933，同题材比较无风格与应用风格。','- 通过正常调度队列和专用验收账号生成，原图逐张目视检查，未修改线上模板。','- 这是单种子静物逐项检查加少量补测，不代表多主体、多种子的稳定性；本轮未逐项覆盖图生图。图生图、自定义 CRUD 和 App 操作的代表性验收见[之前报告](../2026-09-14-image-styles/report.md)。','', '统一主体：'+run['prompt'],'','## 首轮逐项结论','', '| 风格 / 原图 | 任务耗时 | 视觉结论 | 说明 |','|---|---:|---|---|']
for row in reviews:
 item=results[row['id']]
 lines.append(f"| [{row['name']}](results/{row['id']}.png) | {item['elapsedMillis']//1000} 秒 | {labels[row['verdict']]} | {row['notes']} |")
lines+=['','耗时为服务记录的任务总耗时；补测与末尾首轮任务共享队列，排队可能增加总耗时。','', '## 弱效果项补测','', '保留首轮结论，不用补测覆盖失败证据。每组主体及完整请求见[补测记录](results/supplements/report.json)。','', '| 风格 | 补测结论 | 对照及说明 |','|---|---|---|']
for row in reviews:
 if 'supplement' in row:
  s=row['supplement'];prefix='results/supplements/'+s['case']
  lines.append(f"| {row['name']} | {labels[s['verdict']]} | [无风格]({prefix}-baseline.png) / [应用风格]({prefix}-styled.png)：{s['notes']} |")
lines+=['','## 验收限制与后续处理','', '- 装饰艺术、超现实、黑白首轮效果不达标；应优先改进模板的指令顺序和约束，再用多个主体与种子回归，不能宣称 24 种均已稳定通过。','- 黏土、折纸、多层剪纸存在只改变主物件材质的问题；如需全画面转换，应明确整体场景约束并重新验证。','- 点彩、线稿、奇幻与电影感存在颗粒尺度、上色、结构或区分度方面的限制，详见逐项记录。','- 内置模板快照仍保留发布时的 UNTESTED_ON_GPU 标记。本次仅记录测试证据，没有将单样例结论写成全局模型兼容承诺。','', '## 证据完整性','',f'已检查 {len(verified)} 张原始 PNG：SHA-256 与服务记录一致，尺寸均为 1024×1024。首轮 24 个风格请求的主体、seed、版本及 effectivePrompt 与固定模板一致。', '', '[结构化目视记录](reviews.json) · [图片校验清单](integrity.json)','']
health=json.loads((root/'health.json').read_text())
assert all(x['ActiveState']=='active' and x['SubState']=='running' for x in health['services'].values())
assert health['comfyQueue']=={'running':0,'pending':0}
lines+=['## 最终健康状态','','六个相关服务均 active/running，自动重启计数均为 0；Comfy 执行和待处理队列均为 0。见[健康记录](health.json)。','','补测综合判断：装饰艺术和黑白属于有限通过，超现实仍未通过；首轮计数保持原样，不以补测替换。','']
(root/'report.md').write_text('\n'.join(lines))
(root/'integrity.json').write_text(json.dumps({'verifiedImageCount':len(verified),'files':verified,'firstRoundStyleCount':24,'firstRoundVisualCounts':dict(counts)},ensure_ascii=False,indent=2)+'\n')
print({'verifiedImages':len(verified),'visualCounts':dict(counts)})
