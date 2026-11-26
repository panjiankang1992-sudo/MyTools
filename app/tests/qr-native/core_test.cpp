#include "qr_engine_core.h"
#include <cassert>
#include <iostream>
#include <stdexcept>

int main() {
    // 中文、换行、链接及接近容量上限的内容必须完整往返。
    for (const std::string text : {std::string("https://example.com/a?q=1"), std::string(u8"二维码测试\nMyTools"), std::string(2900, 'x'), std::string(2950, 'y')}) {
        auto pixels = EncodeQr(text);
        auto values = DecodeQr(pixels.bytes, pixels.width, pixels.width);
        std::cerr << "case bytes=" << text.size() << " decoded=" << values.size() << " length=" << (values.empty()?0:values[0].size()) << "\n";
        assert(values.size() == 1 && values[0] == text);
        // 镜像图片仍应能够识别。
        for (int y = 0; y < pixels.width; ++y) for (int x = 0; x < pixels.width / 2; ++x)
            for (int c = 0; c < 4; ++c) std::swap(pixels.bytes[(y*pixels.width+x)*4+c],pixels.bytes[(y*pixels.width+pixels.width-1-x)*4+c]);
        values = DecodeQr(pixels.bytes, pixels.width, pixels.width);
        assert(values.size() == 1 && values[0] == text);
    }
    assert(DecodeQr(std::vector<uint8_t>(128*128*4,255),128,128).empty());
    bool rejected=false;try{EncodeQr(std::string(2951,'x'));}catch(const std::exception&){rejected=true;}assert(rejected);
    rejected=false;try{DecodeQr({},4096,4096);}catch(const std::exception&){rejected=true;}assert(rejected);
    std::cout << "PASS: UTF-8, URL, long QR, mirror, empty image, invalid bounds\n";
}
