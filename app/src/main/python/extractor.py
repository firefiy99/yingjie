"""萤截 核心解析模块：基于 yt-dlp 实现链接解析、视频/音频下载、文案提取。

与 Android 端通过 JSON 字符串交互，进度通过 Java 回调对象上报。
视频模式：B站等 DASH 平台分别下载视频流+音频流，由 Android 端 MediaMuxer 合并；
抖音/快手等单文件平台直接下载含音视频的单文件。
"""

import json
import os
import re
import sys

# 防止运行时生成 .pyc 缓存导致更新 APK 后仍加载旧代码
sys.dont_write_bytecode = True
import io
sys.pycache_prefix = None

import yt_dlp


def _diag(tag: str = "") -> str:
    """诊断：返回当前模块的加载路径和方法清单，方便定位运行时加载异常。"""
    import os
    m = sys.modules.get(__name__)
    funcs = sorted(n for n in dir(m) if not n.startswith("_")) if m else []
    f = getattr(m, "__file__", "?")
    return "[%s] file=%s funcs=%s" % (tag, f, ",".join(funcs))


def _link_from_text(text: str) -> str:
    """从分享口令/任意文本中提取第一个 http(s) 链接。"""
    m = re.search(r"https?://[^\s，。；、\"'<>]+", text)
    if m:
        return m.group(0).rstrip(".,;!?")
    raise ValueError("未找到有效的视频链接")


def test() -> str:
    funcs = sorted(n for n in dir(sys.modules[__name__]) if not n.startswith("_"))
    return "yt-dlp %s | %s" % (yt_dlp.version.__version__, ",".join(funcs))


def _friendly(e: Exception) -> str:
    """把 yt-dlp 异常转成可读的中文提示。"""
    msg = str(e)
    low = msg.lower()
    if "unsupported url" in low:
        return "不支持的链接或平台"
    if "unable to download webpage" in low or "timed out" in low or "connection" in low or "resolve" in low:
        return "网络连接失败，请检查网络后重试"
    if "http error 403" in low or "http error 401" in low:
        return "视频需要登录或已失效（403/401）"
    if "video unavailable" in low or "private" in low or "removed" in low:
        return "视频不存在、已删除或为私密内容"
    if "sign in" in low or "login" in low or "log in" in low:
        return "该内容需要登录后才能获取"
    if "geo" in low or "region" in low:
        return "视频受地区限制，无法获取"
    return msg[:200]


def _cookie_host(url: str) -> str:
    """根据链接判断 cookie 所属域名（抖音\/快手）。"""
    u = (url or "").lower()
    if "kuaishou" in u:
        return "www.kuaishou.com"
    return "www.douyin.com"


def _cookie_tmp_dir() -> str:
    """返回可写临时目录：优先用 app 的 cacheDir（Android 上 /tmp 可能不存在）。"""
    try:
        from com.chaquo.python import Python
        ctx = Python.getApplicationContext()
        d = str(ctx.getCacheDir().getAbsolutePath())
        if d:
            return d
    except Exception:
        pass
    import tempfile
    return tempfile.gettempdir()


def _apply_cookie(opts: dict, cookie, host: str = "www.douyin.com") -> None:
    """把 Cookie 导入 yt-dlp。

    两个动作：
    1. 把 cookie 字符串写成 Netscape cookies.txt，通过 cookiefile 参数导入
       yt-dlp 的 cookie jar（抖音提取器用 _get_cookies() 检查 s_v_web_id，
       只塞 http_headers 不会进 jar，会导致 "Fresh cookies are needed"）；
    2. 同时追加 Cookie 请求头，保持与旧版一致的服务器端行为。

    cookies.txt 放在系统临时目录，可被多次调用覆盖复用。
    """
    if not cookie:
        return
    # 1. 写 cookies.txt 并导入 cookie jar
    try:
        import http.cookiejar

        jar_path = os.path.join(_cookie_tmp_dir(), "yingjie_cookies_%s.txt" % host.strip(".").replace(".", "_"))
        jar = http.cookiejar.MozillaCookieJar(jar_path)
        # 同一域名下的多个 cookie 可能重复出现，先按 name 去重（保留最后一个值）
        pairs = {}
        for part in cookie.split(";"):
            part = part.strip()
            if "=" not in part:
                continue
            k, v = part.split("=", 1)
            pairs[k.strip()] = v.strip()
        for name, value in pairs.items():
            if not name or not value:
                continue
            c = http.cookiejar.Cookie(
                version=0, name=name, value=value,
                port=None, port_specified=False,
                domain=host, domain_specified=True, domain_initial_dot=host.startswith("."),
                path="/", path_specified=True,
                secure=False, expires=None, discard=True,
                comment=None, comment_url=None, rest={}, rfc2109=False,
            )
            jar.set_cookie(c)
        jar.save(ignore_discard=True, ignore_expires=True)
        opts["cookiefile"] = jar_path
    except Exception:
        # 写文件失败时退回到请求头方式，不阻断提取
        pass
    # 2. 请求头也带上（部分平台按 header 校验）
    headers = dict(opts.get("http_headers") or {})
    headers["Cookie"] = cookie
    opts["http_headers"] = headers


def _make_progress_hook(progress_callback):
    def hook(d):
        try:
            if d.get("status") == "downloading":
                total = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
                done = d.get("downloaded_bytes") or 0
                progress_callback.onProgress(done, total, str(d.get("_percent_str", "")).strip())
            elif d.get("status") == "finished":
                progress_callback.onProgress(1, 1, "finished")
        except Exception:
            pass
    return hook


def _download_one(url: str, out_dir: str, fmt: str, cookie, progress_callback):
    """下载单个格式，返回 (文件路径, info dict)。"""
    ydl_opts = {
        "outtmpl": os.path.join(out_dir, "%(title).80s [%(id)s].%(ext)s"),
        "format": fmt,
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
        "retries": 3,
        "socket_timeout": 30,
    }
    _apply_cookie(ydl_opts, cookie, _cookie_host(url))
    if progress_callback is not None:
        ydl_opts["progress_hooks"] = [_make_progress_hook(progress_callback)]

    with yt_dlp.YoutubeDL(ydl_opts) as ydl:
        info = ydl.extract_info(url, download=True)
    if not info:
        raise RuntimeError("下载失败：未获取到视频信息")

    filename = ydl.prepare_filename(info)
    ext = info.get("ext") or ""
    candidates = [filename]
    if ext:
        candidates.append(filename.rsplit(".", 1)[0] + "." + ext)
    for c in candidates:
        if os.path.exists(c):
            return c, info
    # 兜底：目录里最新文件
    if os.path.isdir(out_dir):
        files = sorted(
            os.listdir(out_dir),
            key=lambda f: os.path.getmtime(os.path.join(out_dir, f)),
            reverse=True,
        )
        if files:
            return os.path.join(out_dir, files[0]), info
    raise RuntimeError("下载完成但未找到输出文件")


def extract_json(url_or_text: str, cookie=None) -> str:
    """解析链接，返回 JSON 字符串（title/description/duration/uploader/platform）。"""
    try:
        url = _link_from_text(url_or_text)
    except Exception as e:
        return json.dumps({"error": str(e)}, ensure_ascii=False)
    opts = {
        "quiet": True,
        "no_warnings": True,
        "skip_download": True,
        "noplaylist": True,
    }
    _apply_cookie(opts, cookie, _cookie_host(url))
    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=False)
    except Exception as e:
        return json.dumps({"error": _friendly(e)}, ensure_ascii=False)
    if not info:
        return json.dumps({"error": "解析失败：未获取到视频信息"}, ensure_ascii=False)
    return json.dumps({
        "title": info.get("title") or "未命名",
        "description": (info.get("description") or "").strip(),
        "duration": info.get("duration") or 0,
        "uploader": info.get("uploader") or "",
        "platform": (info.get("extractor_key") or "").lower(),
        "webpage_url": info.get("webpage_url") or url,
    }, ensure_ascii=False)


def download_json(url_or_text: str, mode: str, out_dir: str, cookie=None, ffmpeg_path=None, progress_callback=None) -> str:
    """下载。返回 JSON：
    - 单文件：{"path", "title", "ext"}
    - DASH 分离（B站）：{"video_path", "audio_path", "title", "ext"}
    - 失败：{"error"}
    """
    try:
        url = _link_from_text(url_or_text)
    except Exception as e:
        return json.dumps({"error": str(e)}, ensure_ascii=False)

    if mode == "video":
        # 探测格式：是否 DASH 音视频分离
        try:
            probe_opts = {"quiet": True, "no_warnings": True, "skip_download": True, "noplaylist": True}
            _apply_cookie(probe_opts, cookie, _cookie_host(url))
            with yt_dlp.YoutubeDL(probe_opts) as ydl:
                pinfo = ydl.extract_info(url, download=False)
        except Exception as e:
            return json.dumps({"error": _friendly(e)}, ensure_ascii=False)
        if not pinfo:
            return json.dumps({"error": "解析失败：未获取到视频信息"}, ensure_ascii=False)

        title = pinfo.get("title") or "未命名"
        fmts = pinfo.get("formats") or []
        has_video_only = any(
            (f.get("vcodec") or "none") != "none" and (f.get("acodec") or "none") == "none"
            for f in fmts
        )
        has_audio_only = any(
            (f.get("acodec") or "none") != "none" and (f.get("vcodec") or "none") == "none"
            for f in fmts
        )

        if has_video_only and has_audio_only:
            # B站等 DASH：分别下载视频流 + 音频流，交给 Android 端 MediaMuxer 合并
            try:
                vpath, _ = _download_one(url, out_dir, "bestvideo", cookie, progress_callback)
                apath, _ = _download_one(url, out_dir, "bestaudio", cookie, progress_callback)
            except Exception as e:
                return json.dumps({"error": _friendly(e)}, ensure_ascii=False)
            return json.dumps({
                "video_path": vpath,
                "audio_path": apath,
                "title": title,
                "ext": "mp4",
            }, ensure_ascii=False)
        else:
            # 单文件平台（抖音/快手等）：直接下载含音视频的单文件
            try:
                path, info = _download_one(url, out_dir, "best", cookie, progress_callback)
            except Exception as e:
                return json.dumps({"error": _friendly(e)}, ensure_ascii=False)
            return json.dumps({
                "path": path,
                "title": title,
                "ext": info.get("ext") or "mp4",
            }, ensure_ascii=False)

    elif mode == "audio":
        try:
            path, info = _download_one(url, out_dir, "bestaudio/best", cookie, progress_callback)
        except Exception as e:
            return json.dumps({"error": _friendly(e)}, ensure_ascii=False)
        return json.dumps({
            "path": path,
            "title": info.get("title") or "未命名",
            "ext": info.get("ext") or "m4a",
        }, ensure_ascii=False)

    else:
        return json.dumps({"error": "未知提取模式"}, ensure_ascii=False)
