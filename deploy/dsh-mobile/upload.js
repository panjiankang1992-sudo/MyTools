/* MyTools 公网 DSH 的大请求分片传输。 */
(function () {
  "use strict";

  const publicOrigin = "https://dsh-app.yuyutian.top";
  if (window.location.origin !== publicOrigin) return;

  const installed = Symbol.for("mytools.dsh.upload");
  const fetchOriginal = window.fetch;
  if (fetchOriginal[installed]) return;

  const partBytes = 262144;
  const maximumBytes = 300 * 1024 * 1024;
  const parallelParts = 4;
  const attempts = 3;
  const requestTimeout = 75000;
  const commitTimeout = 180000;
  const retryableStatuses = new Set([408, 429, 502, 503, 504]);
  const unavailableStatuses = new Set([404, 405, 501]);
  const relayPath = "/_mytools/upload/";
  const targets = new Map([
    ["/api/session/prompt", "prompt"],
    ["/api/fileUploads/upload", "file-json"]
  ]);

  function uploadUrl(input) {
    if (typeof input !== "string" && !(input instanceof URL)) return undefined;
    const url = new URL(input, document.baseURI);
    if (url.origin !== publicOrigin || url.hash || url.username || url.password) return undefined;
    return url;
  }

  function supportedPost(init) {
    return init && typeof init.method === "string" && init.method.toUpperCase() === "POST" &&
      !init.keepalive && !init.integrity && init.credentials !== "omit" && init.mode !== "no-cors" &&
      init.cache !== "only-if-cached" && init.redirect !== "manual" &&
      (init.signal == null || init.signal instanceof AbortSignal);
  }

  function contentType(init) {
    // 代理仅提供固定的请求头。带有其他请求头时沿用原来的发送方式。
    const headers = new Headers(init.headers);
    if ([...headers.keys()].some(name => name !== "content-type")) return "";
    return headers.get("content-type") || "";
  }

  function relayTarget(input, init) {
    try {
      const url = uploadUrl(input);
      if (!url || url.search) return undefined;
      const target = targets.get(url.pathname);
      if (!target || !supportedPost(init) || typeof init.body !== "string" ||
          init.body.length * 3 <= partBytes || init.body.length > maximumBytes ||
          !/^application\/json(?:\s*;\s*charset\s*=\s*"?utf-8"?)?$/i.test(contentType(init))) return undefined;
      return target;
    } catch (_) {
      return undefined;
    }
  }

  function binaryQuery(input, init) {
    try {
      const url = uploadUrl(input);
      if (!url || url.pathname !== "/api/session/uploadFileBinary" || !supportedPost(init) ||
          !(init.body instanceof Blob) || init.body.size <= partBytes || init.body.size > maximumBytes ||
          !/^application\/octet-stream$/i.test(contentType(init))) return undefined;

      // 拒绝含坏编码的输入，避免转换成另一个文件名后才提交。
      decodeURIComponent(url.search.slice(1));
      const fields = url.searchParams;
      const limits = new Map([["sessionId", 256], ["name", 1024], ["mediaType", 255]]);
      const seen = new Set();
      const encoder = new TextEncoder();
      for (const [key, value] of fields) {
        const limit = limits.get(key);
        if (!limit || seen.has(key) || !value || encoder.encode(value).length > limit ||
            /[\u0000-\u001f\u007f-\u009f]/.test(value)) return undefined;
        if (key === "mediaType" &&
            !/^[a-z0-9][a-z0-9!#$&^_.+-]{0,126}\/[a-z0-9][a-z0-9!#$&^_.+-]{0,126}$/i.test(value)) {
          return undefined;
        }
        seen.add(key);
      }
      if (!seen.has("sessionId")) return undefined;
      const query = fields.toString();
      return query.length <= 4700 ? query : undefined;
    } catch (_) {
      return undefined;
    }
  }

  function abortReason(signal) {
    return signal.reason ?? new DOMException("The operation was aborted.", "AbortError");
  }

  function checkAborted(signal) {
    if (signal?.aborted) throw abortReason(signal);
  }

  function linkAbort(signal, controller) {
    if (!signal) return () => {};
    const cancel = () => controller.abort(abortReason(signal));
    if (signal.aborted) cancel();
    else signal.addEventListener("abort", cancel, { once: true });
    return () => signal.removeEventListener("abort", cancel);
  }

  async function withDeadline(signal, milliseconds, operation) {
    const controller = new AbortController();
    const unlink = linkAbort(signal, controller);
    const timeout = new DOMException("DSH upload timed out.", "TimeoutError");
    let expired = false;
    const timer = setTimeout(() => {
      expired = true;
      controller.abort(timeout);
    }, milliseconds);
    try {
      checkAborted(controller.signal);
      const result = await operation(controller.signal);
      checkAborted(controller.signal);
      return result;
    } catch (error) {
      checkAborted(signal);
      if (expired) throw timeout;
      throw error;
    } finally {
      clearTimeout(timer);
      unlink();
    }
  }

  function pause(milliseconds, signal) {
    checkAborted(signal);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        signal.removeEventListener("abort", cancel);
        resolve();
      }, milliseconds);
      function cancel() {
        clearTimeout(timer);
        reject(abortReason(signal));
      }
      signal.addEventListener("abort", cancel, { once: true });
    });
  }

  function discard(response) {
    if (response.body) response.body.cancel().catch(() => {});
  }

  async function request(path, init, signal) {
    for (let attempt = 0; attempt < attempts; attempt++) {
      const lastAttempt = attempt === attempts - 1;
      try {
        const response = await withDeadline(signal, requestTimeout, currentSignal =>
          fetchOriginal.call(window, publicOrigin + relayPath + path, {
            ...init,
            credentials: "same-origin",
            mode: "same-origin",
            redirect: "error",
            referrerPolicy: "origin",
            cache: "no-store",
            signal: currentSignal
          }));
        checkAborted(signal);
        // 完成后的上游响应即使是错误，也属于这次提交的最终结果。
        if (lastAttempt || response.headers.get("X-Dsh-Upload-State") === "complete" ||
            !retryableStatuses.has(response.status)) return response;
        discard(response);
      } catch (error) {
        checkAborted(signal);
        if (lastAttempt || (!(error instanceof TypeError) && error?.name !== "TimeoutError")) throw error;
      }
      await pause(250 * (2 ** attempt), signal);
    }
  }

  async function upload(blob, target, signal, sendOriginal, targetHeaders = {}) {
    checkAborted(signal);
    const transfer = new AbortController();
    const unlink = linkAbort(signal, transfer);
    try {
      const capability = await request("status", { method: "GET" }, transfer.signal);
      if (unavailableStatuses.has(capability.status)) {
        discard(capability);
        return sendOriginal();
      }
      if (!capability.ok) return capability;
      if (capability.status !== 204 || capability.headers.get("X-MyTools-Dsh-Upload") !== "1") {
        discard(capability);
        throw new TypeError("Unexpected DSH upload capability response.");
      }

      const uploadHeaders = {
        "X-Dsh-Upload-Id": crypto.randomUUID(),
        "X-Dsh-Upload-Size": String(blob.size)
      };
      const count = Math.ceil(blob.size / partBytes);
      let next = 0;
      async function sendParts() {
        while (next < count) {
          checkAborted(transfer.signal);
          const index = next++;
          const start = index * partBytes;
          const result = await request("part", {
            method: "PUT",
            headers: {
              ...uploadHeaders,
              "X-Dsh-Upload-Index": String(index),
              "Content-Type": "application/octet-stream"
            },
            body: blob.slice(start, start + partBytes)
          }, transfer.signal);
          if (result.status !== 204) {
            discard(result);
            throw new TypeError("DSH upload part failed with HTTP " + result.status + ".");
          }
        }
      }
      await Promise.all(Array.from({ length: Math.min(parallelParts, count) }, () => sendParts()));

      // 所有查询使用同一个提交编号。结果未知时不会改用原接口重发。
      return await withDeadline(transfer.signal, commitTimeout, async currentSignal => {
        const commit = {
          method: "POST",
          headers: { ...uploadHeaders, "X-Dsh-Upload-Target": target, ...targetHeaders }
        };
        for (;;) {
          const response = await request("commit", commit, currentSignal);
          const state = response.headers.get("X-Dsh-Upload-State");
          if (state === "complete" || !response.ok) return response;
          discard(response);
          if (response.status !== 202 || state !== "pending") {
            throw new TypeError("Unexpected DSH upload commit response.");
          }
          await pause(1000, currentSignal);
        }
      });
    } catch (error) {
      transfer.abort(error);
      throw error;
    } finally {
      unlink();
    }
  }

  function fetchWithUpload(input, init) {
    const target = relayTarget(input, init);
    const sendOriginal = () => fetchOriginal.apply(this, arguments);
    if (!target) return sendOriginal();
    const blob = new Blob([init.body]);
    if (blob.size <= partBytes || blob.size > maximumBytes) return sendOriginal();
    return upload(blob, target, init.signal, sendOriginal);
  }

  function fetchBinaryWithUpload(input, init) {
    const query = binaryQuery(input, init);
    const sendOriginal = () => fetchOriginal.call(window, input, init);
    if (!query) return sendOriginal();
    return upload(init.body, "file-binary", init.signal, sendOriginal, { "X-Dsh-Upload-Query": query });
  }

  Object.defineProperty(fetchWithUpload, installed, { value: true });
  window.fetch = fetchWithUpload;
  // DSH 自己在创建文件上传运行时前读取此入口。保留已有的自定义传输。
  if (!("__DSH_FILE_UPLOAD__" in window) && /arkweb|harmony/i.test(window.navigator?.userAgent || "")) {
    window.__DSH_FILE_UPLOAD__ = { fetch: fetchBinaryWithUpload };
  }
})();
