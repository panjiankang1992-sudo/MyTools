"""媒体标签生成脚本测试。"""

import importlib.util
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import call, patch

SCRIPT_PATH = Path(__file__).parents[1] / "scripts" / "main.py"
SERVICE_ROOT = Path(__file__).parents[5]
sys.path.insert(0, str(SERVICE_ROOT / "task-executor-service" / "sdk" / "python"))
SPEC = importlib.util.spec_from_file_location("media_generate_tags", SCRIPT_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ModelResponse:
    """提供 urllib 响应使用的受限上下文管理接口。"""

    def __init__(self, body):
        self.body = body

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def read(self, maximum_bytes=-1):
        """读取受限响应正文。"""
        return self.body if maximum_bytes < 0 else self.body[:maximum_bytes]


class MediaGenerateTagsTest(unittest.TestCase):
    """在不连接 Ollama 的情况下验证脚本契约。"""

    def test_detected_jpeg_reaches_visual_model_despite_original_binary_mime(self):
        """发布识别结果应覆盖空泛 MIME，并把原始二进制路径送入视觉模型。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "request" / "attachment.bin"
            source.parent.mkdir()
            source.write_bytes(b"image-content")
            context = root / "context.json"
            context.write_text(json.dumps({
                "parameters": {"assetMimeType": "application/octet-stream"},
                "stepOutputs": {
                    "download_asset": {"fileName": "attachment.bin", "relativePath": "request/attachment.bin"},
                    "publish_asset": {"fileName": "attachment.jpg", "mimeType": "image/jpeg"}
                }}))
            with patch.dict(os.environ, {"TASK_CONTEXT_FILE": str(context), "DOWNLOAD_DESTINATION_ROOT": str(root)}):
                parameters = MODULE.load_parameters()
            self.assertEqual("attachment.jpg", parameters["filename"])
            self.assertEqual("image/jpeg", parameters["mimeType"])
            _, payload = MODULE.build_request(parameters)
            self.assertTrue(payload["messages"][0]["images"])

    def test_internal_budget_leaves_executor_cleanup_margin(self):
        """视觉准备与模型调用应为 180 秒步骤保留清理余量。"""
        total = MODULE.VISUAL_PREPARATION_TIMEOUT_SECONDS + MODULE.MODEL_TOTAL_TIMEOUT_SECONDS
        self.assertLessEqual(total, 170)
        self.assertEqual(2, MODULE.MODEL_MAX_ATTEMPTS)

    def test_generates_normalized_result(self):
        """脚本应生成受限且去重的结果。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "sample.jpg"
            source.write_bytes(b"image")
            context = root / "context.json"
            result = root / "result.json"
            context.write_text(json.dumps({"parameters": {
                "sourcePath": str(source),
                "filename": "sample.jpg",
                "mimeType": "image/jpeg",
                "contentSha256": "a" * 64,
            }}), encoding="utf-8")
            model_response = {"tags": [
                {"tag_name": "nature", "tag_type": "topic", "confidence": 1.4},
                {"tag_name": "nature", "tag_type": "topic", "confidence": 0.5},
                {"tag_name": "photo", "confidence": -1},
            ]}
            with patch.dict(os.environ, {"TASK_CONTEXT_FILE": str(context), "TASK_RESULT_FILE": str(result)}), \
                    patch.object(MODULE, "call_model", return_value=model_response):
                MODULE.main()
            generated = json.loads(result.read_text(encoding="utf-8"))
            self.assertEqual("a" * 64, generated["contentSha256"])
            self.assertEqual(["nature", "photo"], [tag["name"] for tag in generated["tags"]])
            self.assertEqual([1.0, 0.0], [tag["confidence"] for tag in generated["tags"]])

    def test_uses_download_step_output_as_tagging_input(self):
        """下载文件应复用通用媒体标签器。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            downloaded = root / "request" / "clip.mp4"
            downloaded.parent.mkdir()
            downloaded.write_bytes(b"video")
            context = root / "context.json"
            context.write_text(json.dumps({"parameters": {"itemId": "item-1"}, "stepOutputs": {
                "download_asset": {"relativePath": "request/clip.mp4", "fileName": "clip.mp4",
                                   "contentSha256": "c" * 64}}}), encoding="utf-8")
            with patch.dict(os.environ, {"TASK_CONTEXT_FILE": str(context),
                                         "DOWNLOAD_DESTINATION_ROOT": str(root)}):
                parameters = MODULE.load_parameters()
            self.assertEqual(str(downloaded), parameters["sourcePath"])
            self.assertEqual("video/mp4", parameters["mimeType"])
            self.assertEqual("c" * 64, parameters["contentSha256"])

    def test_prefers_explicit_mime_type_over_asset_mime_type(self):
        """显式 MIME 应优先于资产元数据和文件名推断。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "asset.txt"
            source.write_bytes(b"image")
            context = root / "context.json"
            context.write_text(json.dumps({"parameters": {
                "sourcePath": str(source), "filename": "asset.txt",
                "mimeType": " Image/WebP ", "assetMimeType": "text/plain",
            }}), encoding="utf-8")

            with patch.dict(os.environ, {"TASK_CONTEXT_FILE": str(context)}, clear=True):
                parameters = MODULE.load_parameters()

            self.assertEqual("image/webp", parameters["mimeType"])

    def test_uses_asset_mime_type_before_filename_inference(self):
        """显式 MIME 缺失时应采用资产 MIME，而不是扩展名推断。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "asset.txt"
            source.write_bytes(b"image")
            context = root / "context.json"
            context.write_text(json.dumps({"parameters": {
                "sourcePath": str(source), "filename": "asset.txt",
                "assetMimeType": "image/png",
            }}), encoding="utf-8")

            with patch.dict(os.environ, {"TASK_CONTEXT_FILE": str(context)}, clear=True):
                parameters = MODULE.load_parameters()

            self.assertEqual("image/png", parameters["mimeType"])

    def test_extensionless_image_uses_visual_branch(self):
        """无扩展名图片应依靠资产 MIME 进入视觉模型分支。"""
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "asset"
            source.write_bytes(b"visual-bytes")
            parameters = self.parameters(source, assetMimeType="image/jpeg")
            parameters.pop("mimeType")

            _, payload = MODULE.build_request(parameters)

            message = payload["messages"][0]
            self.assertEqual(b"visual-bytes", MODULE.base64.b64decode(message["images"][0]))
            self.assertIn("visual content", message["content"])

    def test_rejects_malformed_or_oversized_mime_type(self):
        """MIME 必须满足受限 type/subtype 格式。"""
        invalid_values = ("image/jpeg; charset=binary", "image", "*/*", 42,
                          "a/" + "b" * MODULE.MAX_MIME_TYPE_LENGTH)
        for value in invalid_values:
            with self.subTest(value=value):
                with self.assertRaisesRegex(ValueError, "mimeType is invalid"):
                    MODULE.validated_mime_type(value)

    def test_resolves_storage_source_from_the_same_execution_workspace(self):
        """托管对象标签步骤应从同一 execution 读取最新下载尝试的 source.bin。"""
        with tempfile.TemporaryDirectory() as directory:
            execution = Path(directory) / "task" / "execution"
            stale = execution / "download_asset" / "1" / "source.bin"
            source = execution / "download_asset" / "2" / "source.bin"
            work_dir = execution / "generate_tags" / "1"
            for path, content in ((stale, b"stale"), (source, b"current")):
                path.parent.mkdir(parents=True)
                path.write_bytes(content)
            work_dir.mkdir(parents=True)
            context = work_dir / "task-context.json"
            context.write_text(json.dumps({"parameters": {"itemId": "item-1"}, "stepOutputs": {
                "download_asset": {"fileName": "photo.png", "contentSha256": "d" * 64}
            }}), encoding="utf-8")

            with patch.dict(os.environ, {"TASK_CONTEXT_FILE": str(context),
                                         "TASK_WORK_DIR": str(work_dir)}, clear=True):
                parameters = MODULE.load_parameters()

            self.assertEqual(str(source.resolve()), parameters["sourcePath"])
            self.assertEqual("photo.png", parameters["filename"])
            self.assertEqual("image/png", parameters["mimeType"])
            self.assertEqual("d" * 64, parameters["contentSha256"])
            self.assertNotIn("sourcePath", json.loads(context.read_text(encoding="utf-8"))["stepOutputs"]
                             ["download_asset"])

    def test_resolves_remote_storage_source_without_a_physical_output_path(self):
        """远端对象标签步骤应识别 download_remote_asset 与 remote-source.bin。"""
        with tempfile.TemporaryDirectory() as directory:
            execution = Path(directory) / "task" / "execution"
            source = execution / "download_remote_asset" / "1" / "remote-source.bin"
            work_dir = execution / "generate_tags" / "1"
            source.parent.mkdir(parents=True)
            source.write_bytes(b"remote")
            work_dir.mkdir(parents=True)
            context = work_dir / "task-context.json"
            context.write_text(json.dumps({"parameters": {
                "itemId": "item-2", "sourcePath": "provider/folder/clip.mp4"
            }, "stepOutputs": {
                "download_remote_asset": {"fileName": "clip.mp4", "contentSha256": "e" * 64}
            }}), encoding="utf-8")

            with patch.dict(os.environ, {"TASK_CONTEXT_FILE": str(context),
                                         "TASK_WORK_DIR": str(work_dir)}, clear=True):
                parameters = MODULE.load_parameters()

            self.assertEqual(str(source.resolve()), parameters["sourcePath"])
            self.assertEqual("video/mp4", parameters["mimeType"])
            self.assertEqual("e" * 64, parameters["contentSha256"])

    def test_rejects_workspace_artifact_symlinks_that_escape_the_execution(self):
        """工作区解析不得跟随指向 execution 外部的符号链接。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            execution = root / "task" / "execution"
            attempt = execution / "download_asset" / "1"
            work_dir = execution / "generate_tags" / "1"
            attempt.mkdir(parents=True)
            work_dir.mkdir(parents=True)
            outside = root / "outside.bin"
            outside.write_bytes(b"outside")
            (attempt / "source.bin").symlink_to(outside)

            with patch.dict(os.environ, {"TASK_WORK_DIR": str(work_dir)}, clear=True):
                resolved = MODULE.resolve_workspace_artifact("download_asset", "source.bin")

            self.assertIsNone(resolved)

    def test_main_writes_non_retryable_protocol_error(self):
        """模型协议错误应写入不可重试的稳定错误文档。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "sample.jpg"
            source.write_bytes(b"image")
            context = root / "context.json"
            result = root / "result.json"
            error = root / "error.json"
            context.write_text(json.dumps({"parameters": self.parameters(source)}), encoding="utf-8")
            environment = {"TASK_CONTEXT_FILE": str(context), "TASK_RESULT_FILE": str(result),
                           "TASK_ERROR_FILE": str(error)}
            with patch.dict(os.environ, environment), \
                    patch.object(MODULE, "call_model",
                                 side_effect=MODULE.PermanentModelError("invalid response")):
                with self.assertRaises(MODULE.PermanentModelError):
                    MODULE.main()
            document = json.loads(error.read_text(encoding="utf-8"))
            self.assertEqual("MEDIA_TAGGING_PROVIDER_RESPONSE_INVALID", document["code"])
            self.assertFalse(document["retryable"])

    def test_prefers_existing_thumbnail_over_large_source(self):
        """已有受限缩略图时不应解码大尺寸原图。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "large.png"
            source.write_bytes(b"x" * (MODULE.MAX_IMAGE_BYTES + 1))
            thumbnail = root / "thumbnail.jpg"
            thumbnail.write_bytes(b"thumbnail")
            parameters = self.parameters(source, thumbnailPath=str(thumbnail))

            with patch.object(MODULE, "transcode_visual_input") as transcode:
                _, payload = MODULE.build_request(parameters)

            transcode.assert_not_called()
            encoded = payload["messages"][0]["images"][0]
            self.assertEqual(b"thumbnail", MODULE.base64.b64decode(encoded))

    def test_transcodes_large_source_and_removes_temporary_file(self):
        """缩略图缺失时应为大图生成受限临时 JPEG。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "large.png"
            source.write_bytes(b"x" * (MODULE.MAX_IMAGE_BYTES + 1))
            generated_paths = []

            def run_ffmpeg(command, **kwargs):
                generated_paths.append(Path(command[-1]))
                Path(command[-1]).write_bytes(b"jpeg-fallback")
                self.assertFalse(kwargs["shell"])
                self.assertEqual(MODULE.VISUAL_PREPARATION_TIMEOUT_SECONDS, kwargs["timeout"])
                return subprocess.CompletedProcess(command, 0)

            with patch.dict(os.environ, {"TASK_WORK_DIR": str(root)}), \
                    patch.object(MODULE, "resolve_ffmpeg_binary",
                                 return_value=Path("/usr/bin/ffmpeg")), \
                    patch.object(MODULE.subprocess, "run", side_effect=run_ffmpeg) as process:
                _, payload = MODULE.build_request(self.parameters(
                    source, thumbnailPath=str(root / "missing.jpg")))

            process.assert_called_once()
            self.assertEqual("/usr/bin/ffmpeg", process.call_args.args[0][0])
            encoded = payload["messages"][0]["images"][0]
            self.assertEqual(b"jpeg-fallback", MODULE.base64.b64decode(encoded))
            self.assertTrue(generated_paths)
            self.assertFalse(generated_paths[0].parent.exists())

    def test_transcodes_video_source_when_thumbnail_is_missing(self):
        """视频兜底应发送静态帧而不是原始视频字节。"""
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "clip.mp4"
            source.write_bytes(b"video")
            parameters = self.parameters(source, mimeType="video/mp4")
            with patch.object(MODULE, "transcode_visual_input", return_value=b"frame") as transcode:
                _, payload = MODULE.build_request(parameters)
            transcode.assert_called_once_with(source)
            encoded = payload["messages"][0]["images"][0]
            self.assertEqual(b"frame", MODULE.base64.b64decode(encoded))

    def test_reads_only_bounded_text_sample(self):
        """文本标签请求不应把整个大文件载入内存。"""
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "sample.txt"
            source.write_bytes(b"a" * MODULE.MAX_TEXT_BYTES + b"tail-marker")
            _, payload = MODULE.build_request(self.parameters(source, mimeType="text/plain"))
            content = payload["messages"][0]["content"]
            self.assertNotIn("tail-marker", content)
            self.assertIn("a" * 100, content)

    def test_retries_transient_http_failure_once(self):
        """可重试服务响应应触发一次带抖动的重试。"""
        response = json.dumps({"message": {"content": '{"tags":[]}'}}).encode("utf-8")
        failure = MODULE.urllib.error.HTTPError("http://ollama", 503, "busy", {}, None)
        with patch.object(MODULE.urllib.request, "urlopen",
                          side_effect=[failure, ModelResponse(response)]) as opener, \
                patch.object(MODULE.random, "uniform", return_value=0.1), \
                patch.object(MODULE.time, "sleep") as sleep:
            result = MODULE.call_model("http://ollama/api/chat", {"model": "test"})
        self.assertEqual({"tags": []}, result)
        self.assertEqual(2, opener.call_count)
        sleep.assert_called_once_with(0.6)

    def test_does_not_retry_permanent_http_rejection(self):
        """永久服务拒绝应直接失败而不再次请求。"""
        failure = MODULE.urllib.error.HTTPError("http://ollama", 400, "bad request", {}, None)
        with patch.object(MODULE.urllib.request, "urlopen", side_effect=failure) as opener, \
                patch.object(MODULE.time, "sleep") as sleep:
            with self.assertRaisesRegex(MODULE.PermanentModelError, "HTTP 400"):
                MODULE.call_model("http://ollama/api/chat", {"model": "test"})
        self.assertEqual(1, opener.call_count)
        sleep.assert_not_called()

    def test_does_not_retry_invalid_success_response(self):
        """格式错误的成功响应应属于永久协议错误。"""
        with patch.object(MODULE.urllib.request, "urlopen",
                          return_value=ModelResponse(b"not-json")) as opener, \
                patch.object(MODULE.time, "sleep") as sleep:
            with self.assertRaisesRegex(MODULE.PermanentModelError, "invalid JSON"):
                MODULE.call_model("http://ollama/api/chat", {"model": "test"})
        self.assertEqual(1, opener.call_count)
        sleep.assert_not_called()

    def test_reports_retryable_and_permanent_failures(self):
        """执行器错误文档应阻止协议错误被盲目重试。"""
        with patch.dict(os.environ, {"TASK_ERROR_FILE": "/tmp/task-error.json"}), \
                patch.object(MODULE, "write_task_error") as write_error:
            MODULE.report_task_failure(MODULE.TransientModelError("temporary"))
            MODULE.report_task_failure(MODULE.PermanentModelError("invalid"))
            MODULE.report_task_failure(MODULE.PermanentProviderRejectionError("rejected"))
            MODULE.report_task_failure(MODULE.VisualPreparationConfigurationError("misconfigured"))
        self.assertEqual([
            call("MEDIA_TAGGING_PROVIDER_UNAVAILABLE", "TRANSIENT",
                 "MEDIA_TAGGING_PROVIDER_UNAVAILABLE"),
            call("MEDIA_TAGGING_PROVIDER_RESPONSE_INVALID", "PERMANENT",
                 "MEDIA_TAGGING_PROVIDER_RESPONSE_INVALID"),
            call("MEDIA_TAGGING_PROVIDER_REJECTED", "PERMANENT",
                 "MEDIA_TAGGING_PROVIDER_REJECTED"),
            call("MEDIA_TAGGING_RUNTIME_MISCONFIGURED", "PERMANENT",
                 "MEDIA_TAGGING_RUNTIME_MISCONFIGURED"),
        ], write_error.call_args_list)

    def test_classifies_ffmpeg_timeout_as_retryable_timeout(self):
        """受限 ffmpeg 超时应允许调度器稍后再尝试一次。"""
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "large.jpg"
            source.write_bytes(b"x" * (MODULE.MAX_IMAGE_BYTES + 1))
            with patch.object(MODULE, "resolve_ffmpeg_binary",
                              return_value=Path("/usr/bin/ffmpeg")), \
                    patch.object(MODULE.subprocess, "run",
                              side_effect=subprocess.TimeoutExpired("ffmpeg", 25)):
                with self.assertRaises(MODULE.VisualPreparationTimeout):
                    MODULE.read_visual_input(source, None)

    def test_rejects_relative_ffmpeg_configuration(self):
        """相对路径不能影响受限脚本的可执行文件解析。"""
        with patch.dict(os.environ, {"FFMPEG_BINARY": "ffmpeg"}):
            with self.assertRaises(MODULE.VisualPreparationConfigurationError):
                MODULE.resolve_ffmpeg_binary()

    def test_rejects_noncanonical_ffmpeg_configuration(self):
        """软链接或其他非 canonical 路径必须在启动进程前失败。"""
        candidate = Path("/usr/bin/ffmpeg")
        with patch.dict(os.environ, {"FFMPEG_BINARY": str(candidate)}), \
                patch.object(MODULE.Path, "resolve", return_value=Path("/opt/ffmpeg")), \
                patch.object(MODULE.Path, "lstat"):
            with self.assertRaises(MODULE.VisualPreparationConfigurationError):
                MODULE.resolve_ffmpeg_binary()

    def test_rejects_missing_ffmpeg_configuration(self):
        """不存在的绝对路径必须按确定性配置错误失败。"""
        with patch.dict(os.environ, {"FFMPEG_BINARY": "/usr/bin/ffmpeg"}), \
                patch.object(MODULE.Path, "resolve", side_effect=FileNotFoundError):
            with self.assertRaises(MODULE.VisualPreparationConfigurationError):
                MODULE.resolve_ffmpeg_binary()

    def test_rejects_untrusted_ffmpeg_configuration(self):
        """非 root 所有或可写的二进制必须按永久配置错误拒绝。"""
        candidate = Path("/usr/bin/ffmpeg")
        metadata = os.stat_result((stat.S_IFREG | 0o777, 0, 0, 1, 501, 0, 0, 0, 0, 0))
        with patch.dict(os.environ, {"FFMPEG_BINARY": str(candidate)}), \
                patch.object(MODULE.Path, "resolve", return_value=candidate), \
                patch.object(MODULE.Path, "lstat", return_value=metadata), \
                patch.object(MODULE.os, "access", return_value=True):
            with self.assertRaises(MODULE.VisualPreparationConfigurationError):
                MODULE.resolve_ffmpeg_binary()

    def test_rejects_non_executable_ffmpeg_configuration(self):
        """当前服务账号不可执行的二进制必须在创建子进程前失败。"""
        candidate = Path("/usr/bin/ffmpeg")
        file_metadata = os.stat_result((stat.S_IFREG | 0o755, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        with patch.dict(os.environ, {"FFMPEG_BINARY": str(candidate)}), \
                patch.object(MODULE.Path, "resolve", return_value=candidate), \
                patch.object(MODULE.Path, "lstat", return_value=file_metadata), \
                patch.object(MODULE.os, "access", return_value=False):
            with self.assertRaises(MODULE.VisualPreparationConfigurationError):
                MODULE.resolve_ffmpeg_binary()

    def test_uses_trusted_absolute_ffmpeg_configuration(self):
        """可信绝对路径应原样传给 subprocess 且不经过 shell。"""
        candidate = Path("/usr/bin/ffmpeg")
        file_metadata = os.stat_result((stat.S_IFREG | 0o755, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        directory_metadata = os.stat_result((stat.S_IFDIR | 0o755, 0, 0, 1, 0, 0, 0, 0, 0, 0))

        def metadata(path):
            return file_metadata if path == candidate else directory_metadata

        with patch.dict(os.environ, {"FFMPEG_BINARY": str(candidate)}), \
                patch.object(MODULE.Path, "resolve", return_value=candidate), \
                patch.object(MODULE.Path, "lstat", autospec=True, side_effect=metadata), \
                patch.object(MODULE.os, "access", return_value=True):
            self.assertEqual(candidate, MODULE.resolve_ffmpeg_binary())

    @staticmethod
    def parameters(source, **overrides):
        """构建最小有效图片标签参数。"""
        parameters = {
            "sourcePath": str(source),
            "filename": source.name,
            "mimeType": "image/jpeg",
            "contentSha256": "a" * 64,
        }
        parameters.update(overrides)
        return parameters


if __name__ == "__main__":
    unittest.main()
