#!/usr/bin/env python3
"""按真实工作流构造机器复核计划：prompt 直接取自工作流文件，避免手抄走样。"""
import json
import subprocess
from pathlib import Path

R = Path('/opt/yuyutian/mytools/runtime/video-generation')
E, W, IN = R / 'evidence', R / 'workflows', R / 'runtime-v1/ComfyUI/input'
ANCH = Path('/tmp/vlm-anchors')
ANCH.mkdir(exist_ok=True)


def prompt_of(workflow):
    """取正向提示词。"""
    return json.loads((W / workflow).read_text())['4']['inputs']['text']


def frame_of(video, name):
    """抽取某段视频的第 0 帧作为锚点图。"""
    path = ANCH / (name + '.png')
    if not path.exists():
        subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', str(video), '-frames:v', '1', str(path)], check=True)
    return str(path)


CASES = [
    ('T01-t2v-49', E / 'trial-20260914T063810Z/output.mp4', 't2v-workflow.json', [], 'TEXT_TO_VIDEO'),
    ('T01-81', E / 'window-20260914T082046Z/run-03/output.mp4', 't2v-81-workflow.json', [], 'TEXT_TO_VIDEO'),
    ('I01-grey-s42', E / 'window-20260914T065550Z/run-01/output.mp4', 'firstframe-workflow.json',
     [IN / 'p0-subject-a.png'], 'FIRST_FRAME'),
    ('I01-grey-s932', E / 'window-20260914T103811Z/run-01/output.mp4', 'firstframe-seed932-workflow.json',
     [IN / 'p0-subject-a.png'], 'FIRST_FRAME'),
    ('I01-a025-s42', E / 'window-20260914T114940Z/run-01/output.mp4', 'firstframe-blend025-seed42-workflow.json',
     [IN / 'p0-subject-a.png'], 'FIRST_FRAME'),
    ('I01-a025-s932', E / 'window-20260914T112215Z/run-01/output.mp4', 'firstframe-blend025-seed932-workflow.json',
     [IN / 'p0-subject-a.png'], 'FIRST_FRAME'),
    ('R01-two-image', E / 'window-20260914T074325Z/run-01/output.mp4', 'subject-references-workflow.json',
     [IN / 'p0-subject-a.png', IN / 'p0-subject-b.png'], 'SUBJECT_REFERENCES'),
    ('R03-three-image', E / 'window-20260914T082046Z/run-02/output.mp4', 'subject-references-three-workflow.json',
     [IN / 'p0-subject-a.png', IN / 'p0-subject-b.png', IN / 'p0-scene.png'], 'SUBJECT_REFERENCES'),
    ('F01-firstlast', E / 'window-20260914T074325Z/run-03/output.mp4', 'firstlast-workflow.json',
     [IN / 'p0-subject-a.png', IN / 'p0-subject-b.png'], 'FIRST_LAST_FRAMES'),
    ('S01-shot2-s42', E / 'window-20260914T090441Z/run-02/output.mp4', 'storyboard-shot2-workflow.json',
     [IN / 'p0-subject-b.png'], 'FIRST_FRAME'),
    ('V01-restyle-synth', E / 'window-20260914T065550Z/run-03/output.mp4', 'restyle-gray-workflow.json',
     [frame_of(IN / 'p0-source-motion.mp4', 'motion-src')], 'STRUCTURE_RESTYLE'),
    ('V02-scene-gray', E / 'window-20260914T093408Z/run-01/output.mp4', 'restyle-scene-gray-workflow.json',
     [frame_of(IN / 'p0-source-scene.mp4', 'scene-src')], 'STRUCTURE_RESTYLE'),
    ('V02-scene-edge', E / 'window-20260914T093408Z/run-02/output.mp4', 'restyle-scene-edge-workflow.json',
     [frame_of(IN / 'p0-source-scene.mp4', 'scene-src')], 'STRUCTURE_RESTYLE'),
    ('M01-masked', E / 'window-20260914T082046Z/run-01/output.mp4', 'masked-edit-workflow.json',
     [frame_of(IN / 'p0-masked-control.mp4', 'masked-src')], 'MASKED_EDIT'),
]

plan = []
for run_id, video, workflow, anchors, mode in CASES:
    plan.append({'runId': run_id, 'video': str(video), 'prompt': prompt_of(workflow), 'mode': mode,
                 'anchors': [str(a) for a in anchors], 'workdir': str(E / 'vlm' / run_id)})
Path('/tmp/vlm-plan.json').write_text(json.dumps(plan, indent=2, ensure_ascii=False) + '\n')
print('cases:', len(plan))
for item in plan:
    print('  %-18s anchors=%d  %s' % (item['runId'], len(item['anchors']), item['mode']))
