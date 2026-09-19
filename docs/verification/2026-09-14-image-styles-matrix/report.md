# 24 种内置图片风格逐项验收

2026-09-14：首轮 24 种风格和 1 张无风格对照全部成功生成。视觉检查 14 项通过、7 项有限通过、3 项未通过。接口成功不等于风格效果通过。

[打开原图图册](gallery.html) · [验收方法](method.md) · [首轮原始记录](results/report.json) · [固定模板目录](results/catalog.json)

## 条件与范围

- 现网 image-styles-20260914-v1；krea2-local / krea2-turbo-v1；TEXT_TO_IMAGE，1024×1024，每任务 1 张。
- 首轮固定主体、seed 932、风格 v1；弱效果项补测用 seed 933，同题材比较无风格与应用风格。
- 通过正常调度队列和专用验收账号生成，原图逐张目视检查，未修改线上模板。
- 这是单种子静物逐项检查加少量补测，不代表多主体、多种子的稳定性；本轮未逐项覆盖图生图。图生图、自定义 CRUD 和 App 操作的代表性验收见[之前报告](../2026-09-14-image-styles/report.md)。

统一主体：A red teapot and a plate of strawberries on a wooden table beside a window, with a small green potted plant and soft morning light.

## 首轮逐项结论

| 风格 / 原图 | 任务耗时 | 视觉结论 | 说明 |
|---|---:|---|---|
| [水彩](results/watercolor.png) | 48 秒 | 通过 | 透明色层、纸张纹理与自然晕染边缘明确；茶壶、草莓盘、木桌、窗和绿植均保留。 |
| [印象派](results/impressionist.png) | 49 秒 | 通过 | 粗短笔触、碎色和明亮光感清晰，主体完整。 |
| [点彩](results/pointillism.png) | 52 秒 | 有限通过 | 茶壶与背景由明显色点构成，但点较大接近圆形色块，草莓仍较细描；风格可辨识，非精细点描。 |
| [装饰艺术](results/art-deco.png) | 52 秒 | 未通过 | 首轮更像平滑写实静物画，未见明确装饰几何或对称图案。 |
| [新艺术](results/art-nouveau.png) | 52 秒 | 通过 | 流动植物边框和有机曲线明确，主体完整；风格主要体现在装饰边框与线稿。 |
| [波普](results/pop-art.png) | 51 秒 | 通过 | 平涂色块、黑轮廓与网点阴影明确，主体完整；接近漫画波普，非高饱和拼贴路线。 |
| [超现实](results/surrealist.png) | 52 秒 | 未通过 | 首轮为普通静物绘画，没有可辨识梦境或异常空间关系。 |
| [动漫](results/anime.png) | 49 秒 | 通过 | 清晰轮廓、赛璐璐阴影与简化背景明确，茶壶与草莓盘等主体完整。 |
| [漫画](results/comic.png) | 52 秒 | 通过 | 黑色排线、重墨阴影和印刷感明确，较动漫的平滑色块有明显区别。 |
| [线稿](results/line-art.png) | 52 秒 | 有限通过 | 轮廓线明确，但大面积彩色填充和明暗仍保留，更像彩色勾线插画，不能当作纯线稿。 |
| [数字绘画](results/digital-art.png) | 49 秒 | 通过 | 可见概括色块和数字笔触，主体完整；与印象派存在风格重叠，属于宽泛绘画预设。 |
| [奇幻](results/fantasy.png) | 51 秒 | 有限通过 | 魔法光效和华丽装饰可辨识，但整体偏写实，茶壶出现双提柄结构。 |
| [像素](results/pixel.png) | 51 秒 | 通过 | 方形像素网格和阶梯边缘明确，主体完整。 |
| [等距](results/isometric.png) | 51 秒 | 通过 | 俯视与近似平行的插画视角明确，主体完整；不保证工程制图的严格等距角度。 |
| [低多边形](results/low-poly.png) | 51 秒 | 通过 | 茶壶、花盆、叶片和桌面均有明确多边形块面，草莓细节相对写实。 |
| [黏土](results/clay.png) | 52 秒 | 有限通过 | 茶壶和盘子有圆润手工材质，草莓略有塑形感；绿植和背景仍接近照片，未形成完整黏土场景。 |
| [折纸](results/origami.png) | 52 秒 | 有限通过 | 茶壶有折痕与棱角，但草莓、绿植和背景仍写实，纸艺主要作用于主物件。 |
| [多层剪纸](results/paper-cut.png) | 51 秒 | 有限通过 | 茶壶和盘子有明确叠层切边，但草莓、盆栽和背景保持写实；不满足全画面剪纸的预期。 |
| [极简](results/minimalist.png) | 51 秒 | 通过 | 扩大留白、减少背景细节并概括形体，主要物件保留。 |
| [黑白](results/monochrome.png) | 51 秒 | 未通过 | 首轮仍为红茶壶、红草莓与绿植的彩色照片，未达到黑白要求。 |
| [写实摄影](results/photographic.png) | 49 秒 | 通过 | 釉面反光、果实纹理和自然景深可信，主体完整；与无风格对照接近，属于默认摄影方向。 |
| [胶片](results/analog-film.png) | 52 秒 | 通过 | 相较无风格对照可见颗粒、柔化细节和偏绿暗部，主体完整；胶片感属于轻度处理。 |
| [电影感](results/cinematic.png) | 94 秒 | 有限通过 | 暗部、光线反差和景深可见，但与常规摄影接近，电影感区分度有限。 |
| [美食摄影](results/food-photo.png) | 97 秒 | 通过 | 草莓盘置于前景且清晰，茶壶背景虚化，食物纹理与柔和侧光明确。 |

耗时为服务记录的任务总耗时；补测与末尾首轮任务共享队列，排队可能增加总耗时。

## 弱效果项补测

保留首轮结论，不用补测覆盖失败证据。每组主体及完整请求见[补测记录](results/supplements/report.json)。

| 风格 | 补测结论 | 对照及说明 |
|---|---|---|
| 装饰艺术 | 有限通过 | [无风格](results/supplements/art-deco-poster-baseline.png) / [应用风格](results/supplements/art-deco-poster-styled.png)：海报补测出现几何边框、放射线与阶梯建筑，风格可辨识；但违反 No lettering 生成 TRAVEL 文字。综合判断依赖题材，有限通过。 |
| 超现实 | 未通过 | [无风格](results/supplements/surrealist-room-baseline.png) / [应用风格](results/supplements/surrealist-room-styled.png)：房间题材补测只是从照片变为写实绘画，未出现明确异常空间或梦境关系，两次样例均未达到目标风格。 |
| 线稿 | 有限通过 | [无风格](results/supplements/line-art-neutral-baseline.png) / [应用风格](results/supplements/line-art-neutral-styled.png)：去除颜色词后仍保留彩色填充与灰色明暗，轮廓更清晰但仍不是纯黑白线稿。 |
| 黑白 | 通过 | [无风格](results/supplements/monochrome-neutral-baseline.png) / [应用风格](results/supplements/monochrome-neutral-styled.png)：无颜色描述的主体成功生成黑白照片，灰阶明确；首轮含颜色词时失败，因此综合为有限通过，尚不能稳定覆盖有颜色描述的提示词。 |

## 验收限制与后续处理

- 装饰艺术、超现实、黑白首轮效果不达标；应优先改进模板的指令顺序和约束，再用多个主体与种子回归，不能宣称 24 种均已稳定通过。
- 黏土、折纸、多层剪纸存在只改变主物件材质的问题；如需全画面转换，应明确整体场景约束并重新验证。
- 点彩、线稿、奇幻与电影感存在颗粒尺度、上色、结构或区分度方面的限制，详见逐项记录。
- 内置模板快照仍保留发布时的 UNTESTED_ON_GPU 标记。本次仅记录测试证据，没有将单样例结论写成全局模型兼容承诺。

## 证据完整性

已检查 33 张原始 PNG：SHA-256 与服务记录一致，尺寸均为 1024×1024。首轮 24 个风格请求的主体、seed、版本及 effectivePrompt 与固定模板一致。

[结构化目视记录](reviews.json) · [图片校验清单](integrity.json)

## 最终健康状态

六个相关服务均 active/running，自动重启计数均为 0；Comfy 执行和待处理队列均为 0。见[健康记录](health.json)。

补测综合判断：装饰艺术和黑白属于有限通过，超现实仍未通过；首轮计数保持原样，不以补测替换。
