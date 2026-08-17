#define MOBI_READER_CORE_ONLY
#include "../mobi_reader_napi.cpp"

#include <cassert>
#include <cstdio>

namespace {
void WriteU16(std::vector<uint8_t>& data, size_t offset, uint16_t value) {
  data[offset] = static_cast<uint8_t>(value >> 8U);
  data[offset + 1] = static_cast<uint8_t>(value);
}

void WriteU32(std::vector<uint8_t>& data, size_t offset, uint32_t value) {
  data[offset] = static_cast<uint8_t>(value >> 24U);
  data[offset + 1] = static_cast<uint8_t>(value >> 16U);
  data[offset + 2] = static_cast<uint8_t>(value >> 8U);
  data[offset + 3] = static_cast<uint8_t>(value);
}

std::string BuildSyntheticMobi() {
  std::string body = "<a filepos=\"0000000000\">Legacy Second</a><h1>One</h1><p>Alpha</p>"
      "<mbp:pagebreak/><h1>Two</h1><p>Beta</p><img recindex=\"1\">";
  const size_t secondPosition = body.find("<h1>Two");
  assert(secondPosition < 128);
  const std::string position = std::to_string(secondPosition);
  body.replace(12 + (10 - position.size()), position.size(), position);
  constexpr size_t record0Offset = 126;
  constexpr size_t record1Offset = record0Offset + 300;
  const size_t record2Offset = record1Offset + body.size();
  const size_t record3Offset = record2Offset + 100;
  const size_t record4Offset = record3Offset + 100;
  const size_t record5Offset = record4Offset + 11;
  std::vector<uint8_t> data(record5Offset + 8, 0);
  WriteU16(data, 76, 6);
  WriteU32(data, 78, record0Offset);
  WriteU32(data, 86, record1Offset);
  WriteU32(data, 94, static_cast<uint32_t>(record2Offset));
  WriteU32(data, 102, static_cast<uint32_t>(record3Offset));
  WriteU32(data, 110, static_cast<uint32_t>(record4Offset));
  WriteU32(data, 118, static_cast<uint32_t>(record5Offset));
  WriteU16(data, record0Offset, 2);
  WriteU32(data, record0Offset + 4, static_cast<uint32_t>(body.size()));
  WriteU16(data, record0Offset + 8, 1);
  WriteU16(data, record0Offset + 10, 4096);
  WriteU16(data, record0Offset + 12, 0);
  std::copy_n("MOBI", 4, data.begin() + record0Offset + 16);
  WriteU32(data, record0Offset + 20, 232);
  WriteU32(data, record0Offset + 28, 65001);
  WriteU32(data, record0Offset + 36, 6);
  const std::string title = "Synthetic";
  WriteU32(data, record0Offset + 84, 200);
  WriteU32(data, record0Offset + 88, static_cast<uint32_t>(title.size()));
  WriteU32(data, record0Offset + 108, 5);
  WriteU32(data, record0Offset + 128, 0x40);
  WriteU32(data, record0Offset + 244, 2);
  std::copy(title.begin(), title.end(), data.begin() + record0Offset + 200);
  std::copy(body.begin(), body.end(), data.begin() + record1Offset);

  std::copy_n("INDX", 4, data.begin() + record2Offset);
  WriteU32(data, record2Offset + 4, 56);
  WriteU32(data, record2Offset + 24, 1);
  WriteU32(data, record2Offset + 52, 1);
  std::copy_n("TAGX", 4, data.begin() + record2Offset + 56);
  WriteU32(data, record2Offset + 60, 28);
  WriteU32(data, record2Offset + 64, 1);
  const uint8_t definitions[] = {1, 1, 1, 0, 3, 1, 2, 0, 4, 1, 4, 0, 0, 0, 0, 1};
  std::copy(std::begin(definitions), std::end(definitions), data.begin() + record2Offset + 68);

  std::copy_n("INDX", 4, data.begin() + record3Offset);
  WriteU32(data, record3Offset + 4, 56);
  WriteU32(data, record3Offset + 20, 80);
  WriteU32(data, record3Offset + 24, 1);
  data[record3Offset + 56] = 1;
  data[record3Offset + 57] = 'x';
  data[record3Offset + 58] = 7;
  data[record3Offset + 59] = static_cast<uint8_t>(0x80U | secondPosition);
  data[record3Offset + 60] = 0x80;
  data[record3Offset + 61] = 0x80;
  std::copy_n("IDXT", 4, data.begin() + record3Offset + 80);
  WriteU16(data, record3Offset + 84, 56);

  const std::string ncxLabel = "NCX Second";
  data[record4Offset] = static_cast<uint8_t>(0x80U | ncxLabel.size());
  std::copy(ncxLabel.begin(), ncxLabel.end(), data.begin() + record4Offset + 1);
  std::copy_n("EXTH", 4, data.begin() + record0Offset + 248);
  WriteU32(data, record0Offset + 252, 24);
  WriteU32(data, record0Offset + 256, 1);
  WriteU32(data, record0Offset + 260, 201);
  WriteU32(data, record0Offset + 264, 12);
  WriteU32(data, record0Offset + 268, 0);
  const uint8_t image[] = {0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
  std::copy(std::begin(image), std::end(image), data.begin() + record5Offset);
  const std::string path = "/tmp/mytools-mobi-reader-test.mobi";
  std::ofstream output(path, std::ios::binary | std::ios::trunc);
  output.write(reinterpret_cast<const char*>(data.data()), static_cast<std::streamsize>(data.size()));
  return path;
}
}  // namespace

int main() {
  std::vector<uint8_t> output;
  const uint8_t compressed[] = {'a', 'b', 'c', 0x80, 0x18};
  DecompressPalmDoc(compressed, sizeof(compressed), output);
  assert(std::string(output.begin(), output.end()) == "abcabc");
  const std::string path = BuildSyntheticMobi();
  const std::string resourceDirectory = "/tmp/mytools-mobi-reader-resources";
  std::filesystem::remove_all(resourceDirectory);
  const std::string parsed = ParseMobi(path, resourceDirectory);
  assert(parsed.find("\"title\":\"Synthetic\"") != std::string::npos);
  assert(parsed.find("mbp:pagebreak") != std::string::npos);
  assert(parsed.find("\"sectionTitles\":[\"\",\"NCX Second\"]") != std::string::npos);
  assert(parsed.find("\"resources\":{\"1\":\"file://") != std::string::npos);
  assert(parsed.find("\"coverUri\":\"file://") != std::string::npos);
  assert(std::filesystem::exists(resourceDirectory + "/resource-1.png"));
  assert(std::filesystem::exists(resourceDirectory + "/cover.png"));
  assert(!std::filesystem::exists(resourceDirectory + "/resource-1.png.partial"));
  bool rejectedExistingDirectory = false;
  try {
    ParseMobi(path, resourceDirectory);
  } catch (const std::runtime_error& error) {
    rejectedExistingDirectory = std::string(error.what()).find("必须不存在") != std::string::npos;
  }
  assert(rejectedExistingDirectory);
  const std::string failedDirectory = "/tmp/mytools-mobi-reader-failed-resources";
  std::filesystem::remove_all(failedDirectory);
  gFailResourceExtractionAfter = 1;
  bool injectedFailure = false;
  try {
    ParseMobi(path, failedDirectory);
  } catch (const std::runtime_error& error) {
    injectedFailure = std::string(error.what()).find("测试故障") != std::string::npos;
  }
  gFailResourceExtractionAfter = 0;
  assert(injectedFailure);
  assert(!std::filesystem::exists(failedDirectory));
  {
    std::fstream file(path, std::ios::in | std::ios::out | std::ios::binary);
    std::string bytes((std::istreambuf_iterator<char>(file)), std::istreambuf_iterator<char>());
    const size_t master = bytes.find("INDX");
    assert(master != std::string::npos);
    file.clear();
    file.seekp(static_cast<std::streamoff>(master));
    file.put('X');
  }
  const std::string fallback = ParseMobi(path);
  assert(fallback.find("\"sectionTitles\":[\"\",\"Legacy Second\"]") != std::string::npos);
  std::remove(path.c_str());
  std::filesystem::remove_all(resourceDirectory);
  return 0;
}
