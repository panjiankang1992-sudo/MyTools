#pragma once
#include <cstdint>
#include <string>
#include <vector>

/** 本机二维码像素结果，固定为 RGBA 格式。 */
struct QrPixels { int width; std::vector<uint8_t> bytes; };
/** 将 UTF-8 内容编码成包含四模块静区的二维码。 */
QrPixels EncodeQr(const std::string& text);
/** 从受限尺寸 RGBA 图片中识别二维码内容。 */
std::vector<std::string> DecodeQr(const std::vector<uint8_t>& rgba, int width, int height);
