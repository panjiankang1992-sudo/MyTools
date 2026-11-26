# 7-Zip 26.03

Source: https://www.7-zip.org/a/7z2603-src.tar.xz
SHA-256: 9cbde5099c6deb73691b0579063da5827522ccbbcba3f0020fd04e8c8c16c0d4

The complete upstream source tree is preserved under `7zip/`. MyTools builds it as the separate shared library `libsevenzip.so` using the source list from `CPP/7zip/Bundles/Format7zF/makefile.gcc`. No upstream source modification is applied. `SevenZipSources.cmake` supplies the build integration; `archive_engine_core.cpp` and `archive_engine_napi.cpp` are MyTools adapters.

Copyright (C) 1999-2026 Igor Pavlov. Primarily LGPL 2.1 or later, with BSD and unRAR restrictions for specified files. See `7zip/DOC/License.txt`, `copying.txt`, and `unRarLicense.txt`; copies are bundled in the application resources. Source is available at the URL above and in this repository. RAR is used for extraction only.

Rebuild replacement library with the HarmonyOS SDK CMake toolchain and the same ABI as the app; replace `libsevenzip.so` when rebuilding and signing the app. Do not restrict reverse engineering for debugging modifications of this LGPL component. Public binary distribution must include notices and a corresponding-source distribution mechanism consistent with the licenses.
