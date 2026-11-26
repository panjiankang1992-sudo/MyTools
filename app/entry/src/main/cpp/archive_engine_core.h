#pragma once
#include <atomic>
#include <cstdint>
#include <string>
#include <vector>

namespace mytools_archive {
// 每个任务拥有独立的取消与进度状态。
struct State {
  std::atomic<bool> cancel{false};
  std::atomic<uint64_t> bytes{0};
  std::atomic<uint64_t> total{0};
  std::atomic<bool> running{true};
};
struct Request {
  std::string source;
  std::string output;
  std::string password;
  std::vector<uint32_t> selected;
  bool extract = false;
};
// 执行受限的本机预览或解压，结果不包含密码。
std::string Run(const Request& request, State& state);
}
