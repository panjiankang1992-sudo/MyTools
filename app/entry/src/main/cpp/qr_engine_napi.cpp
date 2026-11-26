#include <napi/native_api.h>
#include "qr_engine_core.h"
#include <cstring>
#include <memory>
#include <stdexcept>

namespace {
/** 异步作业仅持有拷贝后的数据，不跨线程访问 ArkTS 对象。 */
struct Work {
    napi_async_work work = nullptr;
    napi_deferred deferred = nullptr;
    bool encode = false;
    std::string text, error;
    std::vector<uint8_t> pixels;
    int width = 0, height = 0;
    QrPixels output{};
    std::vector<std::string> results;
};
void Execute(napi_env, void* value) {
    auto* work = static_cast<Work*>(value);
    try {
        if (work->encode) work->output = EncodeQr(work->text);
        else work->results = DecodeQr(work->pixels, work->width, work->height);
    } catch (const std::exception& error) { work->error = error.what(); }
    catch (...) { work->error = "QR_INTERNAL"; }
}
void Complete(napi_env env, napi_status status, void* value) {
    std::unique_ptr<Work> work(static_cast<Work*>(value));
    napi_value result = nullptr;
    if (status != napi_ok || !work->error.empty()) {
        napi_value message;
        napi_create_string_utf8(env, work->error.empty() ? "QR_CANCELLED" : work->error.c_str(), NAPI_AUTO_LENGTH, &message);
        napi_create_error(env, nullptr, message, &result);
        napi_reject_deferred(env, work->deferred, result);
    } else {
        if (work->encode) {
            void* bytes = nullptr;
            napi_create_arraybuffer(env, work->output.bytes.size(), &bytes, &result);
            std::memcpy(bytes, work->output.bytes.data(), work->output.bytes.size());
        } else {
            napi_create_array_with_length(env, work->results.size(), &result);
            for (size_t i = 0; i < work->results.size(); ++i) {
                napi_value text;
                napi_create_string_utf8(env, work->results[i].data(), work->results[i].size(), &text);
                napi_set_element(env, result, i, text);
            }
        }
        napi_resolve_deferred(env, work->deferred, result);
    }
    napi_delete_async_work(env, work->work);
}
napi_value Run(napi_env env, napi_callback_info info) {
    size_t argc = 3;
    napi_value args[3]; void* mode = nullptr;
    napi_get_cb_info(env, info, &argc, args, nullptr, &mode);
    auto work = std::make_unique<Work>(); work->encode = mode != nullptr;
    bool valid = false;
    if (work->encode && argc == 1) {
        size_t length = 0;
        if (napi_get_value_string_utf8(env, args[0], nullptr, 0, &length) == napi_ok && length > 0 && length <= 2950) {
            std::vector<char> buffer(length + 1);
            napi_get_value_string_utf8(env, args[0], buffer.data(), buffer.size(), &length);
            work->text.assign(buffer.data(), length); valid = true;
        }
    } else if (!work->encode && argc == 3) {
        void* data = nullptr; size_t length = 0;
        if (napi_get_arraybuffer_info(env, args[0], &data, &length) == napi_ok &&
            napi_get_value_int32(env, args[1], &work->width) == napi_ok &&
            napi_get_value_int32(env, args[2], &work->height) == napi_ok &&
            work->width > 0 && work->width <= 2048 && work->height > 0 && work->height <= 2048 &&
            length == static_cast<size_t>(work->width) * work->height * 4) {
            const auto* bytes = static_cast<uint8_t*>(data);
            work->pixels.assign(bytes, bytes + length); valid = true;
        }
    }
    if (!valid) { napi_throw_error(env, "QR_INPUT_INVALID", "QR_INPUT_INVALID"); return nullptr; }
    napi_value promise, name;
    napi_create_promise(env, &work->deferred, &promise);
    napi_create_string_utf8(env, "QrEngine", NAPI_AUTO_LENGTH, &name);
    napi_create_async_work(env, nullptr, name, Execute, Complete, work.get(), &work->work);
    napi_queue_async_work(env, work->work); work.release();
    return promise;
}
napi_value Init(napi_env env, napi_value exports) {
    napi_property_descriptor methods[] = {
        {"encode", nullptr, Run, nullptr, nullptr, nullptr, napi_default, reinterpret_cast<void*>(1)},
        {"decode", nullptr, Run, nullptr, nullptr, nullptr, napi_default, nullptr}
    };
    napi_define_properties(env, exports, 2, methods); return exports;
}
napi_module module = {1, 0, nullptr, Init, "qr_engine", nullptr, {0}};
}
extern "C" __attribute__((constructor)) void RegisterQrEngine() { napi_module_register(&module); }
