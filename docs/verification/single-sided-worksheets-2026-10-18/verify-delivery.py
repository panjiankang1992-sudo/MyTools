"""逐项比对公网入口和关键页面资源与本地生产产物。"""
from pathlib import Path
import hashlib,json,re,subprocess
ROOT=Path(__file__).resolve().parent
DIST=ROOT.parents[2]/'toolbox-web/dist'
manifest=json.loads((ROOT/'release-manifest.json').read_text())
by_path={item['path']:item for item in manifest['files']}
index=(DIST/'index.html').read_text()
paths=['index.html']+[value.lstrip('/') for value in re.findall(r'(?:src|href)="([^"]+\.(?:js|css))"',index)]
paths += [item['path'] for item in manifest['files'] if re.match(r'assets/(TextbookEntryView|TextbookListView|TextbookEditorView|WorksheetPairedEditor|ChineseView|WordsView|curriculum-pinyin|useWorksheetV2Session)-.*\.(js|css)$',item['path'])]
checks=[]
for path in dict.fromkeys(paths):
    response=subprocess.run(['curl','-fsS','--retry','2','--max-time','30','-H','Cache-Control: no-cache',manifest['site']+'/'+path],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    if response.returncode: raise RuntimeError('Public asset fetch failed: '+path)
    actual=hashlib.sha256(response.stdout).hexdigest()
    assert actual==by_path[path]['sha256'],'Public hash mismatch: '+path
    checks.append({'path':path,'sha256':actual,'matched':True})
result={'site':manifest['site'],'releaseId':manifest['releaseId'],'verifiedPublicAssets':len(checks),'checks':checks}
(ROOT/'public-delivery.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({'verifiedPublicAssets':len(checks),'allMatched':True}))
