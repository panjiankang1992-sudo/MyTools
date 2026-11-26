import json,subprocess,hashlib
from pathlib import Path
import numpy as np
from PIL import Image,ImageDraw
root=Path('docs/verification/2026-09-14-video-generation')
out=root/'ai-review-20260921'
reports=[]
for group in ['p0-reaccept-20260920','stability-20260920']:
 for video in sorted((root/group).glob('*.mp4')):
  data=subprocess.check_output(['ffmpeg','-v','error','-threads','2','-i',str(video),'-f','rawvideo','-pix_fmt','rgb24','-'])
  frames=np.frombuffer(data,dtype=np.uint8).reshape(-1,480,832,3)
  content=frames[:,:,176:656].astype(np.float32)
  means=content.mean(axis=(1,2,3))/255
  delta=np.abs(np.diff(content,axis=0)).mean(axis=(1,2,3))
  ident=f'{group}--{video.stem}'
  sheet=Image.new('RGB',(7*224,7*146),'#202020');draw=ImageDraw.Draw(sheet)
  for i,frame in enumerate(frames):
   thumb=Image.fromarray(frame).resize((224,129))
   x=i%7*224;y=i//7*146;sheet.paste(thumb,(x,y));draw.text((x+3,y+130),f'{i:02d} {i/16:.3f}s',fill='white')
  sheet.save(out/(ident+'.jpg'),quality=92)
  report={'sample':ident,'video':str(video),'sha256':hashlib.sha256(video.read_bytes()).hexdigest(),'frames':len(frames),'reviewMethod':'all frames in chronological contact sheet plus decoded frame metrics; not official VBench','blackFrames':np.where(means<.03)[0].tolist(),'darkFrames':np.where(means<.08)[0].tolist(),'duplicateTransitions':np.where(delta==0)[0].tolist(),'adjacentContentMad':{'min':float(delta.min()),'max':float(delta.max()),'median':float(np.median(delta))},'maxMeanLumaStep':float(np.abs(np.diff(means)).max()),'sheet':ident+'.jpg'}
  reports.append(report)
(out/'frame-metrics.json').write_text(json.dumps(reports,indent=2)+'\n')
print(json.dumps({'videos':len(reports),'blackFrames':sum(len(x['blackFrames']) for x in reports),'duplicateTransitions':sum(len(x['duplicateTransitions']) for x in reports)}))
