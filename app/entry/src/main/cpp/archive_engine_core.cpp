#include "archive_engine_core.h"
#include "vendor/7zip/CPP/Common/MyCom.h"
#include "vendor/7zip/CPP/Common/UTFConvert.h"
#include "vendor/7zip/CPP/Windows/PropVariant.h"
#include "vendor/7zip/CPP/7zip/Archive/IArchive.h"
#include "vendor/7zip/CPP/7zip/IPassword.h"
#include <algorithm>
#include <cerrno>
#include <cstring>
#include <filesystem>
#include <fcntl.h>
#include <set>
#include <sstream>
#include <stdexcept>
#include <sys/stat.h>
#include <unistd.h>

extern "C" HRESULT CreateArchiver(const GUID*, const GUID*, void**);
namespace mytools_archive {
namespace {
constexpr uint64_t kLimit = 300ULL * 1024 * 1024;
constexpr uint32_t kEntries = 5000;
struct Entry { uint32_t id; std::string path; uint64_t size; bool directory; bool encrypted; };
std::string Quote(const std::string& value) {
  std::string out = "\"";
  const char* hex = "0123456789abcdef";
  for (unsigned char c : value) {
    if (c == '\\' || c == '"') { out += '\\'; out += c; }
    else if (c < 32) { out += "\\u00"; out += hex[c >> 4]; out += hex[c & 15]; }
    else out += c;
  }
  return out + '"';
}
std::string Utf8(BSTR value) {
  AString text;
  if (value) { UString wide; wide.SetFromBstr(value); ConvertUnicodeToUTF8(wide, text); }
  return std::string(text.Ptr(), text.Len());
}
std::string PropertyText(IInArchive* arc, uint32_t index, PROPID id) {
  NWindows::NCOM::CPropVariant p;
  if (arc->GetProperty(index, id, &p) != S_OK) throw std::runtime_error("ARCHIVE_004");
  return p.vt == VT_BSTR ? Utf8(p.bstrVal) : "";
}
bool PropertyBool(IInArchive* arc, uint32_t index, PROPID id) {
  NWindows::NCOM::CPropVariant p;
  if (arc->GetProperty(index, id, &p) != S_OK) throw std::runtime_error("ARCHIVE_004");
  return p.vt == VT_BOOL && p.boolVal != VARIANT_FALSE;
}
std::string SafePath(std::string value) {
  std::replace(value.begin(), value.end(), '\\', '/');
  if (value.empty() || value.size() > 2048 || value.front() == '/' || value.find(':') != std::string::npos)
    throw std::runtime_error("ARCHIVE_009");
  std::string result;
  std::istringstream parts(value);
  std::string part;
  while (std::getline(parts, part, '/')) {
    if (part == "..") throw std::runtime_error("ARCHIVE_009");
    if (part.empty() || part == ".") continue;
    if (part.size() > 240 || std::any_of(part.begin(), part.end(), [](unsigned char c) { return c < 32; }))
      throw std::runtime_error("ARCHIVE_009");
    if (!result.empty()) result += '/';
    result += part;
  }
  if (result.empty()) throw std::runtime_error("ARCHIVE_009");
  return result;
}
class Input final : public IInStream, public CMyUnknownImp {
 public:
  Z7_IFACES_IMP_UNK_1(IInStream)
  Z7_IFACE_COM7_IMP(ISequentialInStream)
 public:
  int fd;
  State& state;
  Input(int descriptor, State& status) : fd(descriptor), state(status) {}
  ~Input() { if (fd >= 0) close(fd); }
};
Z7_COM7F_IMF(Input::Read(void* data, UInt32 size, UInt32* done)) {
  *done = 0;
  if (state.cancel) return E_ABORT;
  ssize_t n;
  do { n = read(fd, data, size); } while (n < 0 && errno == EINTR);
  if (n < 0) return E_FAIL;
  *done = static_cast<UInt32>(n); return S_OK;
}
Z7_COM7F_IMF(Input::Seek(Int64 offset, UInt32 origin, UInt64* position)) {
  if (state.cancel || origin > 2) return E_ABORT;
  const off_t result = lseek(fd, offset, static_cast<int>(origin));
  if (result < 0) return E_FAIL;
  if (position) *position = static_cast<UInt64>(result);
  return S_OK;
}
class OpenCallback final : public IArchiveOpenCallback, public ICryptoGetTextPassword, public CMyUnknownImp {
 public:
  Z7_IFACES_IMP_UNK_2(IArchiveOpenCallback, ICryptoGetTextPassword)
 public:
  State& state;
  const std::string& password;
  bool asked = false;
  OpenCallback(State& status, const std::string& secret) : state(status), password(secret) {}
};
Z7_COM7F_IMF(OpenCallback::SetTotal(const UInt64*, const UInt64*)) { return state.cancel ? E_ABORT : S_OK; }
Z7_COM7F_IMF(OpenCallback::SetCompleted(const UInt64*, const UInt64*)) { return state.cancel ? E_ABORT : S_OK; }
Z7_COM7F_IMF(OpenCallback::CryptoGetTextPassword(BSTR* target)) {
  asked = true;
  if (state.cancel || password.empty()) return E_ABORT;
  UString value; if (!ConvertUTF8ToUnicode(AString(password.c_str()), value)) return E_FAIL;
  *target = SysAllocStringLen(value.Ptr(), value.Len());
  return *target ? S_OK : E_OUTOFMEMORY;
}
class Output final : public ISequentialOutStream, public CMyUnknownImp {
 public:
  Z7_IFACES_IMP_UNK_1(ISequentialOutStream)
 public:
  int fd;
  State& state;
  std::string& error;
  Output(int descriptor, State& status, std::string& failure) : fd(descriptor), state(status), error(failure) {}
  ~Output() { if (fd >= 0) close(fd); }
};
Z7_COM7F_IMF(Output::Write(const void* data, UInt32 size, UInt32* done)) {
  if (done) *done = 0;
  if (state.cancel) return E_ABORT;
  // 实际输出额度独立于压缩包声明，防止元数据欺骗。
  if (state.bytes.load() + size > kLimit) { error = "ARCHIVE_006"; return E_ABORT; }
  UInt32 offset = 0;
  while (offset < size) {
    const ssize_t n = write(fd, static_cast<const char*>(data) + offset, size - offset);
    if (n < 0 && errno == EINTR) continue;
    if (n <= 0) { error = errno == ENOSPC ? "ARCHIVE_007" : "ARCHIVE_008"; return E_FAIL; }
    offset += static_cast<UInt32>(n);
  }
  state.bytes += size;
  if (done) *done = size;
  return S_OK;
}
class ExtractCallback final : public IArchiveExtractCallback, public ICryptoGetTextPassword, public CMyUnknownImp {
 public:
  Z7_IFACES_IMP_UNK_2(IArchiveExtractCallback, ICryptoGetTextPassword)
  Z7_IFACE_COM7_IMP(IProgress)
 public:
  State& state;
  const Request& request;
  const std::vector<Entry>& entries;
  CMyComPtr<ISequentialOutStream> stream;
  std::string error;
  bool asked = false;
  ExtractCallback(State& status, const Request& task, const std::vector<Entry>& list)
      : state(status), request(task), entries(list) {}
};
Z7_COM7F_IMF(ExtractCallback::SetTotal(UInt64)) { return state.cancel ? E_ABORT : S_OK; }
Z7_COM7F_IMF(ExtractCallback::SetCompleted(const UInt64*)) { return state.cancel ? E_ABORT : S_OK; }
Z7_COM7F_IMF(ExtractCallback::PrepareOperation(Int32)) { return state.cancel ? E_ABORT : S_OK; }
Z7_COM7F_IMF(ExtractCallback::GetStream(UInt32 index, ISequentialOutStream** out, Int32 mode)) {
  *out = nullptr; stream.Release();
  if (state.cancel) return E_ABORT;
  if (mode != NArchive::NExtract::NAskMode::kExtract) return S_OK;
  if (index >= entries.size()) return E_FAIL;
  const auto& item = entries[index];
  try {
    const std::filesystem::path path = std::filesystem::path(request.output) / item.path;
    if (item.directory) { std::filesystem::create_directories(path); return S_OK; }
    std::filesystem::create_directories(path.parent_path());
    // 目标是本任务刚创建的独占目录，拒绝同名覆盖与符号链接。
    const int fd = open(path.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (fd < 0) { error = errno == ENOSPC ? "ARCHIVE_007" : "ARCHIVE_009"; return E_FAIL; }
    stream = new Output(fd, state, error);
    *out = stream; (*out)->AddRef();
    return S_OK;
  } catch (...) { error = "ARCHIVE_008"; return E_FAIL; }
}
Z7_COM7F_IMF(ExtractCallback::SetOperationResult(Int32 result)) {
  stream.Release();
  if (result == NArchive::NExtract::NOperationResult::kOK) return S_OK;
  if (result == NArchive::NExtract::NOperationResult::kWrongPassword) error = "ARCHIVE_003";
  else if (result == NArchive::NExtract::NOperationResult::kUnsupportedMethod) error = "ARCHIVE_001";
  else if (result == NArchive::NExtract::NOperationResult::kUnavailable) error = "ARCHIVE_005";
  else error = "ARCHIVE_004";
  return E_FAIL;
}
Z7_COM7F_IMF(ExtractCallback::CryptoGetTextPassword(BSTR* target)) {
  asked = true;
  if (state.cancel || request.password.empty()) { error = "ARCHIVE_002"; return E_ABORT; }
  UString value; if (!ConvertUTF8ToUnicode(AString(request.password.c_str()), value)) return E_FAIL;
  *target = SysAllocStringLen(value.Ptr(), value.Len());
  return *target ? S_OK : E_OUTOFMEMORY;
}
std::string Result(const std::string& code, const std::string& format, const std::vector<Entry>& entries,
                   uint64_t bytes, bool used) {
  std::string out = "{\"code\":" + Quote(code) + ",\"format\":" + Quote(format) + ",\"entries\":[";
  for (size_t i = 0; i < entries.size(); ++i) {
    const auto& e = entries[i];
    if (i) out += ',';
    out += "{\"id\":" + std::to_string(e.id) + ",\"path\":" + Quote(e.path) + ",\"size\":" +
      std::to_string(e.size) + ",\"directory\":" + (e.directory ? "true" : "false") +
      ",\"encrypted\":" + (e.encrypted ? "true" : "false") + '}';
  }
  return out + "],\"bytes\":" + std::to_string(bytes) + ",\"passwordUsed\":" + (used ? "true}" : "false}");
}
}
std::string Run(const Request& request, State& state) {
  std::vector<Entry> entries;
  std::string format;
  bool used = false;
  bool owned = false;
  auto cleanup = [&]() {
    if (owned) { std::error_code ignored; std::filesystem::remove_all(request.output, ignored); }
  };
  try {
    int fd = open(request.source.c_str(), O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) throw std::runtime_error("ARCHIVE_008");
    CMyComPtr<IInStream> input = new Input(fd, state);
    struct stat info{};
    if (fstat(fd, &info) != 0 || !S_ISREG(info.st_mode) || info.st_size > 500LL * 1024 * 1024)
      throw std::runtime_error("ARCHIVE_006");
    unsigned char magic[8]{}; const ssize_t count = pread(fd, magic, sizeof(magic), 0);
    uint8_t id = 0;
    if (count >= 4 && magic[0] == 'P' && magic[1] == 'K') { id = 1; format = "ZIP"; }
    else if (count >= 6 && memcmp(magic, "7z\xbc\xaf\x27\x1c", 6) == 0) { id = 7; format = "7z"; }
    else if (count >= 7 && memcmp(magic, "Rar!\x1a\x07", 6) == 0) {
      id = magic[6] == 1 ? 0xCC : 3; format = magic[6] == 1 ? "RAR5" : "RAR4";
    } else if (count >= 2 && magic[0] == 0x1f && magic[1] == 0x8b) { id = 0xEF; format = "GZIP"; }
    else { id = 0xEE; format = "TAR"; }
    GUID cls = {0x23170F69, 0x40C1, 0x278A, {0x10, 0, 0, 1, 0x10, id, 0, 0}};
    CMyComPtr<IInArchive> arc;
    if (CreateArchiver(&cls, &IID_IInArchive, reinterpret_cast<void**>(&arc)) != S_OK)
      throw std::runtime_error("ARCHIVE_001");
    auto* opening = new OpenCallback(state, request.password);
    CMyComPtr<IArchiveOpenCallback> openingRef = opening;
    const UInt64 maxSearch = 0;
    const HRESULT opened = arc->Open(input, &maxSearch, openingRef);
    used = opening->asked;
    if (state.cancel) throw std::runtime_error("ARCHIVE_010");
    if (opened != S_OK) throw std::runtime_error(used ? (request.password.empty() ? "ARCHIVE_002" : "ARCHIVE_003") : "ARCHIVE_001");
    UInt32 num = 0;
    if (arc->GetNumberOfItems(&num) != S_OK) throw std::runtime_error("ARCHIVE_004");
    if (num > kEntries) throw std::runtime_error("ARCHIVE_006");
    std::set<std::string> paths;
    uint64_t total = 0;
    for (UInt32 i = 0; i < num; ++i) {
      if (state.cancel) throw std::runtime_error("ARCHIVE_010");
      std::string path = PropertyText(arc, i, kpidPath);
      if (path.empty() && format == "GZIP") path = "content";
      path = SafePath(path);
      std::string key = path;
      std::transform(key.begin(), key.end(), key.begin(), [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
      if (!paths.insert(key).second) throw std::runtime_error("ARCHIVE_009");
      if (!PropertyText(arc, i, kpidSymLink).empty() || !PropertyText(arc, i, kpidHardLink).empty())
        throw std::runtime_error("ARCHIVE_009");
      NWindows::NCOM::CPropVariant attr;
      arc->GetProperty(i, kpidPosixAttrib, &attr);
      if (attr.vt == VT_UI4 && (attr.ulVal & S_IFMT) != 0 && !S_ISREG(attr.ulVal) && !S_ISDIR(attr.ulVal))
        throw std::runtime_error("ARCHIVE_009");
      NWindows::NCOM::CPropVariant size;
      arc->GetProperty(i, kpidSize, &size);
      uint64_t bytes = size.vt == VT_UI8 ? size.uhVal.QuadPart : (size.vt == VT_UI4 ? size.ulVal : 0);
      if (bytes > kLimit || total > kLimit - bytes) throw std::runtime_error("ARCHIVE_006");
      total += bytes;
      entries.push_back({i, path, bytes, PropertyBool(arc, i, kpidIsDir), PropertyBool(arc, i, kpidEncrypted)});
    }
    state.total = total;
    if (request.extract) {
      std::vector<UInt32> selected = request.selected;
      std::sort(selected.begin(), selected.end());
      selected.erase(std::unique(selected.begin(), selected.end()), selected.end());
      if (selected.empty() || selected.back() >= entries.size()) throw std::runtime_error("ARCHIVE_011");
      // 不接受已有目录，避免失败清理误删已有文件。
      owned = std::filesystem::create_directory(request.output);
      if (!owned) throw std::runtime_error("ARCHIVE_008");
      auto* callback = new ExtractCallback(state, request, entries);
      CMyComPtr<IArchiveExtractCallback> callbackRef = callback;
      HRESULT extracted = arc->Extract(selected.data(), static_cast<UInt32>(selected.size()), false, callbackRef);
      used = used || callback->asked;
      if (state.cancel) throw std::runtime_error("ARCHIVE_010");
      if (!callback->error.empty()) throw std::runtime_error(callback->error);
      if (extracted != S_OK) throw std::runtime_error("ARCHIVE_004");
      std::vector<Entry> output;
      for (auto index : selected) {
        auto item = entries[index];
        if (!item.directory) {
          item.size = std::filesystem::file_size(std::filesystem::path(request.output) / item.path);
          output.push_back(item);
        }
      }
      entries = output;
    }
    arc->Close();
    state.running = false;
    return Result("", format, entries, state.bytes, used);
  } catch (const std::exception& e) {
    cleanup(); state.running = false;
    std::string code = e.what();
    if (code.rfind("ARCHIVE_", 0) != 0) code = "ARCHIVE_011";
    return Result(code, format, {}, state.bytes, used);
  } catch (...) {
    cleanup(); state.running = false;
    return Result("ARCHIVE_011", format, {}, state.bytes, used);
  }
}
}
