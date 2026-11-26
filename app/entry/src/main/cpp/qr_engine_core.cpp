#include "qr_engine_core.h"
#include "vendor/qr/qrcodegen/qrcodegen.h"
#include "vendor/qr/quirc/quirc.h"
#include <algorithm>
#include <memory>
#include <stdexcept>

QrPixels EncodeQr(const std::string& text) {
    // 字节上限与页面约束一致，空字节内容不应被静默截断。
    if (text.empty() || text.size() > 2950) throw std::runtime_error("QR_INPUT_INVALID");
    std::vector<uint8_t> work(qrcodegen_BUFFER_LEN_MAX), code(qrcodegen_BUFFER_LEN_MAX);
    std::vector<uint8_t> payload(text.size());
    uint8_t eci[3]{};
    // 显式声明 UTF-8，避免其他扫码器把中文按默认字符集解释。
    qrcodegen_Segment segments[] = {
        qrcodegen_makeEci(26, eci),
        qrcodegen_makeBytes(reinterpret_cast<const uint8_t*>(text.data()), text.size(), payload.data())
    };
    if (!qrcodegen_encodeSegmentsAdvanced(segments, 2, qrcodegen_Ecc_LOW,
            qrcodegen_VERSION_MIN, qrcodegen_VERSION_MAX, qrcodegen_Mask_AUTO, true, work.data(), code.data()))
        throw std::runtime_error("QR_ENCODE_FAILED");
    const int size = qrcodegen_getSize(code.data());
    const int scale = std::max(4, 720 / (size + 8));
    QrPixels result{(size + 8) * scale, {}};
    result.bytes.assign(static_cast<size_t>(result.width) * result.width * 4, 255);
    for (int y = 0; y < result.width; ++y) {
        for (int x = 0; x < result.width; ++x) {
            // 四模块白边是扫码定位所必需的静区。
            if (qrcodegen_getModule(code.data(), x / scale - 4, y / scale - 4)) {
                const size_t offset = (static_cast<size_t>(y) * result.width + x) * 4;
                result.bytes[offset] = result.bytes[offset + 1] = result.bytes[offset + 2] = 0;
            }
        }
    }
    return result;
}

std::vector<std::string> DecodeQr(const std::vector<uint8_t>& rgba, int width, int height) {
    // 限制原生内存分配，拒绝错位的像素缓冲区。
    if (width < 1 || height < 1 || width > 2048 || height > 2048 ||
        rgba.size() != static_cast<size_t>(width) * height * 4) throw std::runtime_error("QR_IMAGE_INVALID");
    std::unique_ptr<quirc, decltype(&quirc_destroy)> decoder(quirc_new(), quirc_destroy);
    if (!decoder || quirc_resize(decoder.get(), width, height) < 0) throw std::runtime_error("QR_DECODE_FAILED");
    auto* gray = quirc_begin(decoder.get(), nullptr, nullptr);
    for (size_t i = 0; i < rgba.size() / 4; ++i) {
        const auto* p = rgba.data() + i * 4;
        // 透明区域按白色处理，避免黑底破坏二维码边界。
        const int luminance = (p[0] * 77 + p[1] * 150 + p[2] * 29) >> 8;
        gray[i] = static_cast<uint8_t>((luminance * p[3] + 255 * (255 - p[3])) / 255);
    }
    quirc_end(decoder.get());
    std::vector<std::string> results;
    for (int i = 0; i < std::min(quirc_count(decoder.get()), 64); ++i) {
        quirc_code code{}; quirc_data data{};
        quirc_extract(decoder.get(), i, &code);
        auto error = quirc_decode(&code, &data);
        if (error != QUIRC_SUCCESS) { quirc_flip(&code); error = quirc_decode(&code, &data); }
        if (error == QUIRC_SUCCESS && data.payload_len > 0) {
            std::string value(reinterpret_cast<char*>(data.payload), data.payload_len);
            if (std::find(results.begin(), results.end(), value) == results.end()) results.push_back(value);
        }
    }
    return results;
}
