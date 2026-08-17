#include <algorithm>
#include <cstdint>
#include <fstream>
#include <filesystem>
#include <regex>
#include <set>
#include <unordered_map>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

#ifndef MOBI_READER_CORE_ONLY
#include "napi/native_api.h"
#endif

namespace {
constexpr size_t kMaxInputBytes = 100U * 1024U * 1024U;
constexpr size_t kMaxOutputBytes = 20U * 1024U * 1024U;
constexpr size_t kMaxResourceBytes = 100U * 1024U * 1024U;
constexpr size_t kMaxResourceCount = 1000U;

#ifdef MOBI_READER_CORE_ONLY
size_t gFailResourceExtractionAfter = 0;
#endif

class ResourceDirectoryTransaction {
 public:
  explicit ResourceDirectoryTransaction(std::filesystem::path directory) : directory_(std::move(directory)) {}
  ResourceDirectoryTransaction(const ResourceDirectoryTransaction&) = delete;
  ResourceDirectoryTransaction& operator=(const ResourceDirectoryTransaction&) = delete;
  ~ResourceDirectoryTransaction() {
    if (!committed_) {
      std::error_code error;
      std::filesystem::remove_all(directory_, error);
    }
  }
  void Commit() { committed_ = true; }

 private:
  std::filesystem::path directory_;
  bool committed_ = false;
};

uint16_t ReadU16(const std::vector<uint8_t>& data, size_t offset) {
  if (offset + 2 > data.size()) throw std::runtime_error("MOBI文件结构不完整");
  return static_cast<uint16_t>((data[offset] << 8U) | data[offset + 1]);
}

uint32_t ReadU32(const std::vector<uint8_t>& data, size_t offset) {
  if (offset + 4 > data.size()) throw std::runtime_error("MOBI文件结构不完整");
  return (static_cast<uint32_t>(data[offset]) << 24U) |
      (static_cast<uint32_t>(data[offset + 1]) << 16U) |
      (static_cast<uint32_t>(data[offset + 2]) << 8U) |
      static_cast<uint32_t>(data[offset + 3]);
}

void AppendUtf8(std::string& output, uint32_t codePoint) {
  if (codePoint <= 0x7F) {
    output.push_back(static_cast<char>(codePoint));
  } else if (codePoint <= 0x7FF) {
    output.push_back(static_cast<char>(0xC0 | (codePoint >> 6U)));
    output.push_back(static_cast<char>(0x80 | (codePoint & 0x3F)));
  } else {
    output.push_back(static_cast<char>(0xE0 | (codePoint >> 12U)));
    output.push_back(static_cast<char>(0x80 | ((codePoint >> 6U) & 0x3F)));
    output.push_back(static_cast<char>(0x80 | (codePoint & 0x3F)));
  }
}

std::string DecodeWindows1252(const std::vector<uint8_t>& input) {
  static constexpr uint16_t kControls[32] = {
      0x20AC, 0xFFFD, 0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021,
      0x02C6, 0x2030, 0x0160, 0x2039, 0x0152, 0xFFFD, 0x017D, 0xFFFD,
      0xFFFD, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014,
      0x02DC, 0x2122, 0x0161, 0x203A, 0x0153, 0xFFFD, 0x017E, 0x0178};
  std::string output;
  output.reserve(input.size() * 2);
  for (uint8_t value : input) {
    uint32_t codePoint = value;
    if (value >= 0x80 && value <= 0x9F) codePoint = kControls[value - 0x80];
    AppendUtf8(output, codePoint);
  }
  return output;
}

void AppendChecked(std::vector<uint8_t>& output, uint8_t value) {
  if (output.size() >= kMaxOutputBytes) throw std::runtime_error("MOBI正文解压后超过20 MB");
  output.push_back(value);
}

void DecompressPalmDoc(const uint8_t* input, size_t length, std::vector<uint8_t>& output) {
  size_t cursor = 0;
  const size_t recordStart = output.size();
  while (cursor < length) {
    uint8_t value = input[cursor++];
    if (value >= 1 && value <= 8) {
      if (cursor + value > length) throw std::runtime_error("PalmDOC字面量越界");
      for (uint8_t index = 0; index < value; ++index) AppendChecked(output, input[cursor++]);
    } else if (value <= 0x7F) {
      AppendChecked(output, value);
    } else if (value >= 0xC0) {
      AppendChecked(output, static_cast<uint8_t>(' '));
      AppendChecked(output, value ^ 0x80U);
    } else {
      if (cursor >= length) throw std::runtime_error("PalmDOC回溯记录不完整");
      uint16_t pair = static_cast<uint16_t>((value << 8U) | input[cursor++]);
      size_t distance = (pair >> 3U) & 0x7FFU;
      size_t count = (pair & 7U) + 3U;
      if (distance == 0 || distance > output.size() - recordStart) {
        throw std::runtime_error("PalmDOC回溯距离非法");
      }
      for (size_t index = 0; index < count; ++index) {
        AppendChecked(output, output[output.size() - distance]);
      }
    }
  }
}

size_t TrailingEntrySize(const uint8_t* input, size_t length) {
  uint32_t value = 0;
  uint32_t shift = 0;
  size_t cursor = length;
  while (cursor > 0 && shift < 28) {
    uint8_t item = input[--cursor];
    value |= static_cast<uint32_t>(item & 0x7FU) << shift;
    if ((item & 0x80U) != 0) return value;
    shift += 7;
  }
  throw std::runtime_error("MOBI尾随记录长度非法");
}

size_t RemoveTrailingEntries(const uint8_t* input, size_t length, uint32_t flags) {
  if (flags == 0) return length;
  size_t contentLength = length;
  uint32_t entries = flags >> 1U;
  while (entries != 0) {
    if ((entries & 1U) != 0) {
      size_t trailingSize = TrailingEntrySize(input, contentLength);
      if (trailingSize == 0 || trailingSize > contentLength) {
        throw std::runtime_error("MOBI尾随记录越界");
      }
      contentLength -= trailingSize;
    }
    entries >>= 1U;
  }
  if ((flags & 1U) != 0) {
    if (contentLength == 0) throw std::runtime_error("MOBI多字节尾随记录缺失");
    size_t multibyteLength = (input[contentLength - 1] & 3U) + 1U;
    if (multibyteLength > contentLength) throw std::runtime_error("MOBI多字节尾随记录越界");
    contentLength -= multibyteLength;
  }
  return contentLength;
}

std::vector<uint8_t> ReadFile(const std::string& path) {
  std::ifstream stream(path, std::ios::binary | std::ios::ate);
  if (!stream) throw std::runtime_error("无法打开MOBI缓存文件");
  std::streamsize length = stream.tellg();
  if (length <= 0 || static_cast<size_t>(length) > kMaxInputBytes) {
    throw std::runtime_error("MOBI为空或超过100 MB");
  }
  stream.seekg(0, std::ios::beg);
  std::vector<uint8_t> data(static_cast<size_t>(length));
  if (!stream.read(reinterpret_cast<char*>(data.data()), length)) throw std::runtime_error("MOBI读取失败");
  return data;
}

std::string JsonEscape(const std::string& value) {
  std::ostringstream output;
  for (unsigned char item : value) {
    switch (item) {
      case '\\': output << "\\\\"; break;
      case '"': output << "\\\""; break;
      case '\b': output << "\\b"; break;
      case '\f': output << "\\f"; break;
      case '\n': output << "\\n"; break;
      case '\r': output << "\\r"; break;
      case '\t': output << "\\t"; break;
      default:
        if (item < 0x20) {
          const char* digits = "0123456789abcdef";
          output << "\\u00" << digits[item >> 4U] << digits[item & 0x0FU];
        } else {
          output << static_cast<char>(item);
        }
    }
  }
  return output.str();
}

std::vector<std::string> ExtractSectionTitles(const std::vector<uint8_t>& text, uint32_t encoding) {
  const std::string raw(text.begin(), text.end());
  const std::regex pagebreak("<\\s*(mbp:)?pagebreak\\b[^>]*>", std::regex_constants::icase);
  std::vector<size_t> sectionEnds;
  for (std::sregex_iterator item(raw.begin(), raw.end(), pagebreak), end; item != end; ++item) {
    sectionEnds.push_back(static_cast<size_t>(item->position()));
  }
  sectionEnds.push_back(raw.size());
  std::vector<std::string> titles(sectionEnds.size());
  const std::regex anchor("<a\\b([^>]*)>", std::regex_constants::icase);
  const std::regex filepos("filepos\\s*=\\s*[\"']?([0-9]+)", std::regex_constants::icase);
  const std::regex closeAnchor("</a\\s*>", std::regex_constants::icase);
  size_t searchOffset = 0;
  size_t accepted = 0;
  while (searchOffset < raw.size() && accepted < 5000) {
    std::match_results<std::string::const_iterator> anchorMatch;
    auto searchBegin = raw.cbegin() + static_cast<std::string::difference_type>(searchOffset);
    if (!std::regex_search(searchBegin, raw.cend(), anchorMatch, anchor)) break;
    size_t anchorStart = searchOffset + static_cast<size_t>(anchorMatch.position());
    size_t labelStart = anchorStart + static_cast<size_t>(anchorMatch.length());
    std::smatch positionMatch;
    const std::string attributes = anchorMatch[1].str();
    if (!std::regex_search(attributes, positionMatch, filepos)) {
      searchOffset = labelStart;
      continue;
    }
    std::match_results<std::string::const_iterator> closeMatch;
    auto labelBegin = raw.cbegin() + static_cast<std::string::difference_type>(labelStart);
    if (!std::regex_search(labelBegin, raw.cend(), closeMatch, closeAnchor)) break;
    size_t labelLength = static_cast<size_t>(closeMatch.position());
    size_t nextOffset = labelStart + labelLength + static_cast<size_t>(closeMatch.length());
    uint64_t filePosition = 0;
    try {
      filePosition = std::stoull(positionMatch[1].str());
    } catch (...) {
      searchOffset = nextOffset;
      continue;
    }
    if (filePosition < raw.size() && labelLength > 0 && labelLength <= 1024) {
      auto section = std::upper_bound(sectionEnds.begin(), sectionEnds.end(), static_cast<size_t>(filePosition));
      if (section != sectionEnds.end()) {
        std::vector<uint8_t> labelBytes(text.begin() + labelStart, text.begin() + labelStart + labelLength);
        std::string label = encoding == 65001 ? std::string(labelBytes.begin(), labelBytes.end()) :
            DecodeWindows1252(labelBytes);
        size_t index = static_cast<size_t>(section - sectionEnds.begin());
        if (titles[index].empty()) titles[index] = label;
        accepted++;
      }
    }
    searchOffset = nextOffset;
  }
  return titles;
}

struct TagDefinition {
  uint8_t tag = 0;
  uint8_t valueCount = 0;
  uint8_t mask = 0;
  uint8_t nextControlByte = 0;
};

uint32_t ReadVarLen(const std::vector<uint8_t>& data, size_t& position, size_t end, size_t* bytesRead = nullptr) {
  uint32_t value = 0;
  size_t count = 0;
  while (position < end && count < 4) {
    uint8_t item = data[position++];
    value = (value << 7U) | (item & 0x7FU);
    count++;
    if ((item & 0x80U) != 0) {
      if (bytesRead != nullptr) *bytesRead = count;
      return value;
    }
  }
  throw std::runtime_error("MOBI INDX可变长整数非法");
}

std::string DecodeMobiBytes(const std::vector<uint8_t>& bytes, uint32_t encoding) {
  return encoding == 65001 ? std::string(bytes.begin(), bytes.end()) : DecodeWindows1252(bytes);
}

std::vector<std::string> ExtractNcxSectionTitles(const std::vector<uint8_t>& data,
    const std::vector<size_t>& offsets, uint32_t indexRecord, uint32_t encoding,
    const std::vector<uint8_t>& text) {
  if (indexRecord == 0xFFFFFFFFU || indexRecord >= offsets.size() - 1) return {};
  const size_t masterStart = offsets[indexRecord];
  const size_t masterEnd = offsets[indexRecord + 1];
  if (masterStart + 56 > masterEnd ||
      std::string(data.begin() + masterStart, data.begin() + masterStart + 4) != "INDX") return {};
  const uint32_t headerLength = ReadU32(data, masterStart + 4);
  const uint32_t indexRecordCount = ReadU32(data, masterStart + 24);
  const uint32_t cncxRecordCount = ReadU32(data, masterStart + 52);
  if (headerLength < 56 || masterStart + headerLength + 12 > masterEnd || indexRecordCount > 1000 ||
      cncxRecordCount > 100 || static_cast<uint64_t>(indexRecord) + indexRecordCount + cncxRecordCount + 1 >=
      offsets.size()) {
    throw std::runtime_error("MOBI NCX主索引边界非法");
  }
  const size_t tagxStart = masterStart + headerLength;
  if (std::string(data.begin() + tagxStart, data.begin() + tagxStart + 4) != "TAGX") {
    throw std::runtime_error("MOBI NCX缺少TAGX定义");
  }
  const uint32_t tagxLength = ReadU32(data, tagxStart + 4);
  const uint32_t controlBytes = ReadU32(data, tagxStart + 8);
  if (tagxLength < 12 || tagxStart + tagxLength > masterEnd || controlBytes == 0 || controlBytes > 32 ||
      (tagxLength - 12) % 4 != 0 || (tagxLength - 12) / 4 > 256) {
    throw std::runtime_error("MOBI TAGX定义非法");
  }
  std::vector<TagDefinition> definitions;
  for (size_t position = tagxStart + 12; position < tagxStart + tagxLength; position += 4) {
    definitions.push_back({data[position], data[position + 1], data[position + 2], data[position + 3]});
  }
  std::unordered_map<uint32_t, std::string> labels;
  size_t cncxBytes = 0;
  for (uint32_t record = 0; record < cncxRecordCount; ++record) {
    const uint32_t recordIndex = indexRecord + indexRecordCount + record + 1;
    size_t position = offsets[recordIndex];
    const size_t end = offsets[recordIndex + 1];
    const uint32_t recordBase = record * 0x10000U;
    while (position < end) {
      const uint32_t labelOffset = recordBase + static_cast<uint32_t>(position - offsets[recordIndex]);
      const uint32_t length = ReadVarLen(data, position, end);
      if (length == 0 || length > 4096 || position + length > end || cncxBytes + length > 5U * 1024U * 1024U) {
        throw std::runtime_error("MOBI CNCX标签边界非法");
      }
      std::vector<uint8_t> bytes(data.begin() + position, data.begin() + position + length);
      labels[labelOffset] = DecodeMobiBytes(bytes, encoding);
      cncxBytes += length;
      position += length;
    }
  }
  struct NcxItem { uint32_t offset; uint32_t labelOffset; uint32_t level; };
  std::vector<NcxItem> items;
  for (uint32_t record = 0; record < indexRecordCount && items.size() < 10000; ++record) {
    const uint32_t recordIndex = indexRecord + record + 1;
    const size_t start = offsets[recordIndex];
    const size_t end = offsets[recordIndex + 1];
    if (start + 28 > end || std::string(data.begin() + start, data.begin() + start + 4) != "INDX") {
      throw std::runtime_error("MOBI NCX子索引非法");
    }
    const uint32_t idxt = ReadU32(data, start + 20);
    const uint32_t entries = ReadU32(data, start + 24);
    if (entries > 10000 || start + idxt + 4U + static_cast<uint64_t>(entries) * 2U > end ||
        std::string(data.begin() + start + idxt, data.begin() + start + idxt + 4) != "IDXT") {
      throw std::runtime_error("MOBI IDXT边界非法");
    }
    for (uint32_t entryIndex = 0; entryIndex < entries && items.size() < 10000; ++entryIndex) {
      size_t position = start + ReadU16(data, start + idxt + 4U + entryIndex * 2U);
      if (position >= end) throw std::runtime_error("MOBI NCX条目偏移非法");
      const uint8_t keyLength = data[position++];
      if (position + keyLength + controlBytes > end) throw std::runtime_error("MOBI NCX条目边界非法");
      position += keyLength;
      const size_t controlsStart = position;
      position += controlBytes;
      size_t controlIndex = 0;
      std::unordered_map<uint8_t, std::vector<uint32_t>> values;
      for (const TagDefinition& definition : definitions) {
        if (definition.nextControlByte == 1) {
          controlIndex++;
          continue;
        }
        if (controlIndex >= controlBytes || definition.mask == 0) continue;
        uint8_t encodedCount = data[controlsStart + controlIndex] & definition.mask;
        uint32_t valueCount = 0;
        uint32_t valueBytes = 0;
        if (encodedCount == definition.mask) {
          if (__builtin_popcount(static_cast<unsigned int>(definition.mask)) > 1) {
            valueBytes = ReadVarLen(data, position, end);
          } else {
            valueCount = 1;
          }
        } else {
          uint8_t shiftedMask = definition.mask;
          while ((shiftedMask & 1U) == 0) {
            shiftedMask >>= 1U;
            encodedCount >>= 1U;
          }
          valueCount = encodedCount;
        }
        std::vector<uint32_t> tagValues;
        if (valueBytes > 0) {
          size_t consumed = 0;
          while (consumed < valueBytes) {
            size_t bytesRead = 0;
            tagValues.push_back(ReadVarLen(data, position, end, &bytesRead));
            consumed += bytesRead;
          }
          if (consumed != valueBytes) throw std::runtime_error("MOBI NCX标签值长度非法");
        } else {
          const uint64_t totalValues = static_cast<uint64_t>(valueCount) * definition.valueCount;
          if (totalValues > 64) throw std::runtime_error("MOBI NCX标签值过多");
          for (uint64_t valueIndex = 0; valueIndex < totalValues; ++valueIndex) {
            tagValues.push_back(ReadVarLen(data, position, end));
          }
        }
        if (!tagValues.empty()) values[definition.tag] = tagValues;
      }
      if (values[1].empty() || values[3].empty()) continue;
      items.push_back({values[1][0], values[3][0], values[4].empty() ? 0U : values[4][0]});
    }
  }
  const std::string raw(text.begin(), text.end());
  const std::regex pagebreak("<\\s*(mbp:)?pagebreak\\b[^>]*>", std::regex_constants::icase);
  std::vector<size_t> sectionEnds;
  for (std::sregex_iterator item(raw.begin(), raw.end(), pagebreak), end; item != end; ++item) {
    sectionEnds.push_back(static_cast<size_t>(item->position()));
  }
  sectionEnds.push_back(raw.size());
  std::vector<std::string> titles(sectionEnds.size());
  for (const NcxItem& item : items) {
    const auto label = labels.find(item.labelOffset);
    if (label == labels.end() || item.offset >= raw.size()) continue;
    auto section = std::upper_bound(sectionEnds.begin(), sectionEnds.end(), item.offset);
    if (section == sectionEnds.end()) continue;
    const size_t sectionIndex = static_cast<size_t>(section - sectionEnds.begin());
    if (titles[sectionIndex].empty() || item.level == 0) titles[sectionIndex] = label->second;
  }
  return titles;
}

std::string ImageExtension(const std::vector<uint8_t>& data, size_t start, size_t end) {
  if (end < start + 4) return "";
  if (data[start] == 0xFF && data[start + 1] == 0xD8 && data[start + 2] == 0xFF) return "jpg";
  if (end >= start + 8 && data[start] == 0x89 && data[start + 1] == 'P' && data[start + 2] == 'N' &&
      data[start + 3] == 'G' && data[start + 4] == 0x0D && data[start + 5] == 0x0A) return "png";
  if (data[start] == 'G' && data[start + 1] == 'I' && data[start + 2] == 'F' && data[start + 3] == '8') return "gif";
  if (end >= start + 12 && data[start] == 'R' && data[start + 1] == 'I' && data[start + 2] == 'F' &&
      data[start + 3] == 'F' && data[start + 8] == 'W' && data[start + 9] == 'E' &&
      data[start + 10] == 'B' && data[start + 11] == 'P') return "webp";
  if (data[start] == 'B' && data[start + 1] == 'M') return "bmp";
  return "";
}

int64_t ReadExthCoverOffset(const std::vector<uint8_t>& data, size_t record0, size_t record0End,
    uint32_t headerLength) {
  if (record0 + 132 > record0End || (ReadU32(data, record0 + 128) & 0x40U) == 0) return -1;
  const size_t exthStart = record0 + 16U + headerLength;
  if (exthStart + 12 > record0End ||
      std::string(data.begin() + exthStart, data.begin() + exthStart + 4) != "EXTH") return -1;
  const uint32_t exthLength = ReadU32(data, exthStart + 4);
  const uint32_t recordCount = ReadU32(data, exthStart + 8);
  if (exthLength < 12 || exthStart + exthLength > record0End || recordCount > 1000) return -1;
  size_t position = exthStart + 12;
  int64_t thumbnail = -1;
  for (uint32_t index = 0; index < recordCount; ++index) {
    if (position + 8 > exthStart + exthLength) return -1;
    const uint32_t type = ReadU32(data, position);
    const uint32_t length = ReadU32(data, position + 4);
    if (length < 8 || position + length > exthStart + exthLength) return -1;
    if ((type == 201 || type == 202) && length >= 12) {
      const uint32_t value = ReadU32(data, position + 8);
      if (type == 201) return value;
      thumbnail = value;
    }
    position += length;
  }
  return thumbnail;
}

struct ExtractedResources {
  std::unordered_map<uint32_t, std::string> uris;
  std::string coverUri;
};

ExtractedResources ExtractResources(const std::vector<uint8_t>& data, const std::vector<size_t>& offsets,
    const std::vector<uint8_t>& text, uint32_t resourceStart, int64_t coverOffset,
    const std::string& outputDirectory) {
  ExtractedResources result;
  if (outputDirectory.empty() || outputDirectory.size() > 4096 || resourceStart == 0xFFFFFFFFU ||
      resourceStart >= offsets.size() - 1) return result;
  std::set<uint32_t> references;
  const std::string raw(text.begin(), text.end());
  const std::regex recindex("recindex\\s*=\\s*[\"']?([0-9]+)", std::regex_constants::icase);
  for (std::sregex_iterator item(raw.begin(), raw.end(), recindex), end;
       item != end && references.size() < kMaxResourceCount; ++item) {
    try {
      const uint64_t value = std::stoull((*item)[1].str());
      if (value > 0 && value <= 100000) references.insert(static_cast<uint32_t>(value));
    } catch (...) {
    }
  }
  std::error_code error;
  if (!std::filesystem::create_directory(outputDirectory, error) || error) {
    throw std::runtime_error("MOBI图片缓存目录必须不存在且可创建");
  }
  ResourceDirectoryTransaction transaction(outputDirectory);
  size_t totalBytes = 0;
  size_t extractedCount = 0;
  auto extractRecord = [&](uint32_t recordIndex, const std::string& stem) -> std::string {
    if (recordIndex >= offsets.size() - 1) return "";
    const size_t start = offsets[recordIndex];
    const size_t end = offsets[recordIndex + 1];
    const std::string extension = ImageExtension(data, start, end);
    if (extension.empty() || end - start > 20U * 1024U * 1024U || totalBytes + end - start > kMaxResourceBytes) {
      return "";
    }
    const std::filesystem::path path = std::filesystem::path(outputDirectory) / (stem + "." + extension);
    const std::filesystem::path temporary = path.string() + ".partial";
    std::filesystem::remove(temporary, error);
    error.clear();
    std::ofstream output(temporary, std::ios::binary | std::ios::trunc);
    output.write(reinterpret_cast<const char*>(data.data() + start), static_cast<std::streamsize>(end - start));
    output.flush();
    const bool complete = output.good();
    output.close();
    if (!complete || output.fail() || std::filesystem::file_size(temporary, error) != end - start || error) {
      std::filesystem::remove(temporary, error);
      throw std::runtime_error("MOBI图片资源写入不完整");
    }
    std::filesystem::rename(temporary, path, error);
    if (error) {
      std::filesystem::remove(temporary, error);
      throw std::runtime_error("无法提交MOBI图片资源文件");
    }
    extractedCount++;
#ifdef MOBI_READER_CORE_ONLY
    if (gFailResourceExtractionAfter > 0 && extractedCount >= gFailResourceExtractionAfter) {
      throw std::runtime_error("MOBI图片资源测试故障");
    }
#endif
    totalBytes += end - start;
    return "file://" + path.string();
  };
  for (uint32_t reference : references) {
    const uint64_t recordIndex = static_cast<uint64_t>(resourceStart) + reference - 1U;
    if (recordIndex >= offsets.size() - 1) continue;
    const std::string uri = extractRecord(static_cast<uint32_t>(recordIndex), "resource-" + std::to_string(reference));
    if (!uri.empty()) result.uris[reference] = uri;
  }
  if (coverOffset >= 0) {
    const uint64_t recordIndex = static_cast<uint64_t>(resourceStart) + static_cast<uint64_t>(coverOffset);
    if (recordIndex < offsets.size() - 1) {
      result.coverUri = extractRecord(static_cast<uint32_t>(recordIndex), "cover");
    }
  }
  transaction.Commit();
  return result;
}

std::string ParseMobi(const std::string& path, const std::string& resourceDirectory = "") {
  std::vector<uint8_t> data = ReadFile(path);
  if (data.size() < 86) throw std::runtime_error("不是有效的Palm数据库文件");
  uint16_t recordCount = ReadU16(data, 76);
  if (recordCount < 2 || recordCount > 10000 || 78U + static_cast<size_t>(recordCount) * 8U > data.size()) {
    throw std::runtime_error("MOBI记录表非法");
  }
  std::vector<size_t> offsets;
  offsets.reserve(recordCount + 1);
  for (uint16_t index = 0; index < recordCount; ++index) {
    size_t offset = ReadU32(data, 78U + static_cast<size_t>(index) * 8U);
    if (offset >= data.size() || (!offsets.empty() && offset <= offsets.back())) {
      throw std::runtime_error("MOBI记录偏移非法");
    }
    offsets.push_back(offset);
  }
  offsets.push_back(data.size());
  size_t record0 = offsets[0];
  if (record0 + 132 > offsets[1] || std::string(data.begin() + record0 + 16, data.begin() + record0 + 20) != "MOBI") {
    throw std::runtime_error("文件缺少MOBI头");
  }
  uint16_t compression = ReadU16(data, record0);
  uint32_t textLength = ReadU32(data, record0 + 4);
  uint16_t textRecords = ReadU16(data, record0 + 8);
  uint16_t encryption = ReadU16(data, record0 + 12);
  uint32_t encoding = ReadU32(data, record0 + 28);
  uint32_t version = ReadU32(data, record0 + 36);
  uint32_t headerLength = ReadU32(data, record0 + 20);
  uint32_t trailingFlags = headerLength >= 228 && record0 + 244 <= offsets[1] ?
      ReadU32(data, record0 + 240) : 0;
  uint32_t indexRecord = headerLength >= 232 && record0 + 248 <= offsets[1] ?
      ReadU32(data, record0 + 244) : 0xFFFFFFFFU;
  uint32_t resourceStart = record0 + 112 <= offsets[1] ? ReadU32(data, record0 + 108) : 0xFFFFFFFFU;
  if (encryption != 0) throw std::runtime_error("不支持DRM或加密MOBI");
  if (version >= 8) throw std::runtime_error("当前文件为KF8/AZW3，基础MOBI6解析器无法读取");
  if (compression == 17480) throw std::runtime_error("当前文件使用Huff/CDIC压缩，暂不支持");
  if (compression != 1 && compression != 2) throw std::runtime_error("MOBI使用了未知压缩算法");
  if (encoding != 65001 && encoding != 1252) throw std::runtime_error("MOBI字符编码暂不支持");
  if (textLength > kMaxOutputBytes) throw std::runtime_error("MOBI正文超过20 MB");
  if (textRecords == 0 || static_cast<size_t>(textRecords) + 1U > recordCount) {
    throw std::runtime_error("MOBI正文记录数量非法");
  }
  std::vector<uint8_t> text;
  text.reserve(std::min(static_cast<size_t>(textLength), kMaxOutputBytes));
  for (uint16_t index = 1; index <= textRecords; ++index) {
    const uint8_t* input = data.data() + offsets[index];
    size_t length = RemoveTrailingEntries(input, offsets[index + 1] - offsets[index], trailingFlags);
    if (compression == 1) {
      for (size_t cursor = 0; cursor < length; ++cursor) AppendChecked(text, input[cursor]);
    } else {
      DecompressPalmDoc(input, length, text);
    }
    if (text.size() >= textLength) break;
  }
  if (text.size() < textLength) throw std::runtime_error("MOBI正文记录提前结束");
  text.resize(textLength);
  uint32_t titleOffset = ReadU32(data, record0 + 84);
  uint32_t titleLength = ReadU32(data, record0 + 88);
  std::vector<uint8_t> titleBytes;
  if (titleLength > 0 && record0 + titleOffset + titleLength <= offsets[1]) {
    titleBytes.assign(data.begin() + record0 + titleOffset, data.begin() + record0 + titleOffset + titleLength);
  }
  std::string body = encoding == 65001 ? std::string(text.begin(), text.end()) : DecodeWindows1252(text);
  std::string title = encoding == 65001 ? std::string(titleBytes.begin(), titleBytes.end()) : DecodeWindows1252(titleBytes);
  const int64_t coverOffset = ReadExthCoverOffset(data, record0, offsets[1], headerLength);
  const ExtractedResources resources = ExtractResources(data, offsets, text, resourceStart, coverOffset,
      resourceDirectory);
  std::vector<std::string> sectionTitles = ExtractSectionTitles(text, encoding);
  try {
    const std::vector<std::string> ncxTitles = ExtractNcxSectionTitles(data, offsets, indexRecord, encoding, text);
    if (ncxTitles.size() == sectionTitles.size()) {
      for (size_t index = 0; index < ncxTitles.size(); ++index) {
        if (!ncxTitles[index].empty()) sectionTitles[index] = ncxTitles[index];
      }
    }
  } catch (const std::runtime_error&) {
    // 可选目录损坏时仍允许读取已通过边界校验的正文。
  }
  std::string titlesJson = "[";
  for (size_t index = 0; index < sectionTitles.size(); ++index) {
    if (index > 0) titlesJson += ",";
    titlesJson += "\"" + JsonEscape(sectionTitles[index]) + "\"";
  }
  titlesJson += "]";
  std::string resourcesJson = "{";
  bool firstResource = true;
  for (const auto& resource : resources.uris) {
    if (!firstResource) resourcesJson += ",";
    firstResource = false;
    resourcesJson += "\"" + std::to_string(resource.first) + "\":\"" + JsonEscape(resource.second) + "\"";
  }
  resourcesJson += "}";
  return "{\"title\":\"" + JsonEscape(title) + "\",\"content\":\"" + JsonEscape(body) +
      "\",\"version\":" + std::to_string(version) + ",\"encoding\":" + std::to_string(encoding) +
      ",\"sectionTitles\":" + titlesJson + ",\"resources\":" + resourcesJson +
      ",\"coverUri\":\"" + JsonEscape(resources.coverUri) + "\"}";
}
}  // namespace

#ifndef MOBI_READER_CORE_ONLY
std::string GetString(napi_env env, napi_value value) {
  size_t length = 0;
  napi_get_value_string_utf8(env, value, nullptr, 0, &length);
  std::vector<char> buffer(length + 1, '\0');
  napi_get_value_string_utf8(env, value, buffer.data(), buffer.size(), &length);
  return std::string(buffer.data(), length);
}

napi_value Parse(napi_env env, napi_callback_info info) {
  size_t argc = 2;
  napi_value args[2] = {nullptr, nullptr};
  napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
  if (argc < 1 || argc > 2) {
    napi_throw_type_error(env, nullptr, "需要提供MOBI文件路径");
    return nullptr;
  }
  try {
    std::string directory = argc == 2 ? GetString(env, args[1]) : "";
    std::string result = ParseMobi(GetString(env, args[0]), directory);
    napi_value value = nullptr;
    napi_create_string_utf8(env, result.c_str(), result.size(), &value);
    return value;
  } catch (const std::exception& error) {
    napi_throw_error(env, nullptr, error.what());
    return nullptr;
  }
}

napi_value Init(napi_env env, napi_value exports) {
  napi_property_descriptor descriptors[] = {
      {"parse", nullptr, Parse, nullptr, nullptr, nullptr, napi_default, nullptr}};
  napi_define_properties(env, exports, 1, descriptors);
  return exports;
}
static napi_module mobi_reader_module = {
    .nm_version = 1,
    .nm_flags = 0,
    .nm_filename = nullptr,
    .nm_register_func = Init,
    .nm_modname = "mobi_reader",
    .nm_priv = nullptr,
    .reserved = {0},
};

extern "C" __attribute__((constructor)) void RegisterMobiReaderModule() {
  napi_module_register(&mobi_reader_module);
}
#endif
