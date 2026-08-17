#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
app_dir="$(cd "${script_dir}/.." && pwd)"
test_binary="$(mktemp -t mytools-mobi-reader-test)"
trap 'rm -f "${test_binary}"' EXIT

c++ -std=c++17 -Wall -Wextra -Werror \
  "${app_dir}/entry/src/main/cpp/tests/mobi_reader_core_test.cpp" \
  -o "${test_binary}"
"${test_binary}"

wrapper="${app_dir}/entry/src/main/ets/features/reader/MobiReaderNative.ets"
grep -Fq 'this.validateResult(result, resourceDirectory)' "${wrapper}"
grep -Fq 'MOBI章节数量超过2000' "${wrapper}"
grep -Fq 'Object.keys(result.resources)' "${wrapper}"
grep -Fq 'const stat = fs.lstatSync(path)' "${wrapper}"
grep -Fq 'stat.isSymbolicLink()' "${wrapper}"
grep -Fq 'MOBI图片资源路径越界' "${wrapper}"
grep -Fq 'MOBI图片资源总量超过100 MB' "${wrapper}"
grep -Fq 'Array.isArray(result.resources)' "${wrapper}"
grep -Fq 'new RegExp(`^resource-${escapedId}' "${wrapper}"

native="${app_dir}/entry/src/main/cpp/mobi_reader_napi.cpp"
grep -Fq 'MOBI图片缓存目录必须不存在且可创建' "${native}"
grep -Fq 'const std::filesystem::path temporary = path.string() + ".partial"' "${native}"
grep -Fq 'std::filesystem::file_size(temporary, error) != end - start' "${native}"
grep -Fq 'std::filesystem::rename(temporary, path, error)' "${native}"
grep -Fq 'class ResourceDirectoryTransaction' "${native}"
grep -Fq 'std::filesystem::remove_all(directory_, error)' "${native}"
grep -Fq 'transaction.Commit()' "${native}"
grep -Fq 'assert(!std::filesystem::exists(failedDirectory))' "${app_dir}/entry/src/main/cpp/tests/mobi_reader_core_test.cpp"
echo "MOBI reader core tests passed"
