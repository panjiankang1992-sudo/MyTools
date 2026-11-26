"""纯文字验收素材定义；不读取或隐式携带首帧图片。"""


def text_cases():
    """返回主体、动作和固定镜头均明确的三个文字生成样例。"""
    return [
        ('product', 'Thin wisps of steam rise continuously from the spout of a red ceramic teapot on a wooden table. The teapot stays still beside a white cup. A fixed close-up camera shows the steam curling upward against a dark plain background. Soft warm light, realistic appearance, one continuous shot.'),
        ('portrait', 'An adult woman faces the camera and waves her right hand gently from side to side twice in greeting, then lowers it and smiles. She wears a plain blue shirt and stands in front of a simple light gray wall. A fixed medium shot shows her face, shoulders, arm and hand clearly. Natural light, one continuous shot.'),
        ('landscape', 'Green leaves on a leafy tree branch sway gently back and forth in a light breeze. The branch is clearly visible against a softly blurred garden background. A fixed close-up camera holds the same composition while the leaves move. Natural daylight, realistic textures, one continuous shot.'),
    ]


def text_dimensions(name):
    """固定文生验收尺寸，避免依赖图片文件推导参数。"""
    if name not in ('product', 'portrait', 'landscape'):
        raise ValueError('unknown_case')
    return (640, 448) if name == 'landscape' else (640, 640)
