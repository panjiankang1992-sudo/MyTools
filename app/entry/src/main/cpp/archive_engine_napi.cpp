#include "archive_engine_core.h"
#include "napi/native_api.h"
#include <map>
#include <memory>
#include <mutex>
#include <stdexcept>

namespace {
std::mutex mutex;
std::map<std::string, std::shared_ptr<mytools_archive::State>> jobs;
struct Work {
  napi_async_work work{};
  napi_deferred deferred{};
  mytools_archive::Request request;
  std::shared_ptr<mytools_archive::State> state;
  std::string id;
  std::string result;
};
std::string String(napi_env env, napi_value value, size_t max = 8192) {
  size_t size = 0;
  if (napi_get_value_string_utf8(env, value, nullptr, 0, &size) != napi_ok || size > max)
    throw std::runtime_error("ARCHIVE_011");
  std::string text(size + 1, '\0');
  napi_get_value_string_utf8(env, value, text.data(), text.size(), &size);
  text.resize(size);
  return text;
}
napi_value Run(napi_env env, napi_callback_info info) {
  size_t count = 7;
  napi_value args[7];
  napi_get_cb_info(env, info, &count, args, nullptr, nullptr);
  auto task = std::make_unique<Work>();
  try {
    if (count != 7) throw std::runtime_error("ARCHIVE_011");
    task->id = String(env, args[0], 100);
    task->request.source = String(env, args[1]);
    task->request.output = String(env, args[2]);
    task->request.password = String(env, args[3], 4096);
    if (napi_get_value_bool(env, args[4], &task->request.extract) != napi_ok) throw std::runtime_error("ARCHIVE_011");
    uint32_t length = 0;
    if (napi_get_array_length(env, args[5], &length) != napi_ok || length > 5000) throw std::runtime_error("ARCHIVE_006");
    for (uint32_t i = 0; i < length; ++i) {
      napi_value value; uint32_t index;
      napi_get_element(env, args[5], i, &value);
      if (napi_get_value_uint32(env, value, &index) != napi_ok) throw std::runtime_error("ARCHIVE_011");
      task->request.selected.push_back(index);
    }
    // 保留协议版本参数，避免后续接口不兼容时静默执行。
    uint32_t version = 0;
    if (napi_get_value_uint32(env, args[6], &version) != napi_ok || version != 1) throw std::runtime_error("ARCHIVE_011");
    task->state = std::make_shared<mytools_archive::State>();
    {
      std::lock_guard<std::mutex> lock(mutex);
      if (jobs.size() >= 2 || jobs.count(task->id)) throw std::runtime_error("ARCHIVE_011");
      jobs[task->id] = task->state;
    }
    napi_value promise, name;
    napi_create_promise(env, &task->deferred, &promise);
    napi_create_string_utf8(env, "ArchiveWork", NAPI_AUTO_LENGTH, &name);
    napi_status created = napi_create_async_work(env, nullptr, name,
      [](napi_env, void* data) {
        auto* task = static_cast<Work*>(data);
        task->result = mytools_archive::Run(task->request, *task->state);
        // 尽早擦除原生密码缓冲，不将密码写入日志或结果。
        volatile char* secret = task->request.password.data();
        for (size_t i = 0; i < task->request.password.size(); ++i) secret[i] = 0;
        task->request.password.clear();
      },
      [](napi_env env, napi_status status, void* data) {
        std::unique_ptr<Work> task(static_cast<Work*>(data));
        { std::lock_guard<std::mutex> lock(mutex); jobs.erase(task->id); }
        if (status != napi_ok) task->result = "{\"code\":\"ARCHIVE_010\",\"format\":\"\",\"entries\":[],\"bytes\":0,\"passwordUsed\":false}";
        napi_value result;
        napi_create_string_utf8(env, task->result.c_str(), task->result.size(), &result);
        napi_resolve_deferred(env, task->deferred, result);
        napi_delete_async_work(env, task->work);
      }, task.get(), &task->work);
    if (created != napi_ok || napi_queue_async_work(env, task->work) != napi_ok) {
      { std::lock_guard<std::mutex> lock(mutex); jobs.erase(task->id); }
      if (task->work) napi_delete_async_work(env, task->work);
      throw std::runtime_error("ARCHIVE_011");
    }
    task.release(); return promise;
  } catch (const std::exception& error) { napi_throw_error(env, "ARCHIVE_011", error.what()); return nullptr; }
}
napi_value Status(napi_env env, napi_callback_info info) {
  size_t count = 2; napi_value args[2];
  napi_get_cb_info(env, info, &count, args, nullptr, nullptr);
  try {
    if (count != 2) throw std::runtime_error("ARCHIVE_011");
    const auto id = String(env, args[0], 100);
    bool cancel = false;
    napi_get_value_bool(env, args[1], &cancel);
    std::shared_ptr<mytools_archive::State> state;
    { std::lock_guard<std::mutex> lock(mutex); auto it = jobs.find(id); if (it != jobs.end()) state = it->second; }
    if (state && cancel) state->cancel = true;
    std::string json = "{\"bytes\":" + std::to_string(state ? state->bytes.load() : 0) +
      ",\"total\":" + std::to_string(state ? state->total.load() : 0) + ",\"running\":" +
      (state && state->running ? "true}" : "false}");
    napi_value result; napi_create_string_utf8(env, json.c_str(), json.size(), &result); return result;
  } catch (...) { napi_throw_error(env, "ARCHIVE_011", "ARCHIVE_011"); return nullptr; }
}
napi_value Init(napi_env env, napi_value exports) {
  napi_property_descriptor functions[] = {
    {"run", nullptr, Run, nullptr, nullptr, nullptr, napi_default, nullptr},
    {"status", nullptr, Status, nullptr, nullptr, nullptr, napi_default, nullptr}
  };
  napi_define_properties(env, exports, 2, functions); return exports;
}
napi_module module = {1, 0, nullptr, Init, "archive_engine", nullptr, {0}};
}
extern "C" __attribute__((constructor)) void RegisterArchiveEngine() { napi_module_register(&module); }
