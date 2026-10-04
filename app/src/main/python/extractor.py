"""萤截 核心解析模块：基于 yt-dlp 实现链接解析、视频/音频下载、文案提取。

与 Android 端通过 JSON 字符串交互，进度通过 Java 回调对象上报。
视频模式：B站等 DASH 平台分别下载视频流+音频流，由 Android 端 MediaMuxer 合并；
抖音/快手等单文件平台直接下载含音视频的单文件。
"""

import json
import os
import random
import re
import sys
import time
import urllib.error
import urllib.request
from urllib.parse import parse_qs, quote, urlparse

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


# ================= 视频号（微信视频号）解析 =================
# 微信视频号不公开直链。可行方案：借助腾讯「元宝」网页版的解析接口（需要元宝登录 Cookie），
# 先取 exportId + generalToken，再调视频号 finder-preview 接口拿直链（stodownload?encfilekey=...）。
# 参考开源实现 github.com/youyouhe/sph-probe（其生产站 sph.smartbid.site 即用此流程）。
# 说明：get_feed_info 用 shortUri（sph token）免 Cookie 只能拿到元数据，拿不到视频直链；
#       直链必须 exportId + generalToken（由元宝解析接口下发）。

_SPH_PARSE_URL = "https://yuanbao.tencent.com/api/weixin/get_parse_result"
_SPH_FEED_URL = "https://channels.weixin.qq.com/finder-preview/api/feed/get_feed_info"

# 元宝 Web 端防爬请求头（与生产站一致，x-* 系列为前端固定签名头）
_SPH_PARSE_HEADERS = {
    "accept": "application/json, text/plain, */*",
    "accept-language": "zh-CN,zh;q=0.9,en;q=0.8",
    "content-type": "application/json",
    "origin": "https://yuanbao.tencent.com",
    "referer": "https://yuanbao.tencent.com/chat/naQivTmsDa/cf4d0079-ed1b-4c55-a3f3-2ca1379727d1",
    "user-agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36",
    "sec-ch-ua": '"Chromium";v="148", "Google Chrome";v="148", "Not/A)Brand";v="99"',
    "sec-ch-ua-mobile": "?0",
    "sec-ch-ua-platform": '"macOS"',
    "sec-fetch-dest": "empty",
    "sec-fetch-mode": "cors",
    "sec-fetch-site": "same-origin",
    "t-userid": "b9575f6b0a8c4a55a08096904a5ef20a",
    "x-agentid": "naQivTmsDa/cf4d0079-ed1b-4c55-a3f3-2ca1379727d1",
    "x-commit-tag": "72282a0d",
    "x-device-id": "1921b001708100d7fa31002b9646bd0cc15a3e2e1f",
    "x-hy106": "",
    "x-hy92": "e963067ffa31002b9646bd0c03000008b1951a",
    "x-hy93": "1921b001708100d7fa31002b9646bd0cc15a3e2e1f",
    "x-id": "b9575f6b0a8c4a55a08096904a5ef20a",
    "x-instance-id": "5",
    "x-language": "zh-CN",
    "x-os_version": "Mac OS(10.15.7)-Blink",
    "x-platform": "mac",
    "x-requested-with": "XMLHttpRequest",
    "x-source": "web",
    "x-web-third-source": "main",
    "x-webdriver": "0",
    "x-webversion": "2.69.0",
    "x-ybuitest": "0",
}

_SPH_FEED_HEADERS = {
    "Accept": "application/json, text/plain, */*",
    "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
    "Connection": "keep-alive",
    "Content-Type": "application/json",
    "Origin": "https://channels.weixin.qq.com",
    "Sec-Fetch-Dest": "empty",
    "Sec-Fetch-Mode": "cors",
    "Sec-Fetch-Site": "same-origin",
    "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36",
    "sec-ch-ua": '"Chromium";v="148", "Google Chrome";v="148", "Not/A)Brand";v="99"',
    "sec-ch-ua-mobile": "?0",
    "sec-ch-ua-platform": '"macOS"',
}


def _sph_rid() -> str:
    """生成接口要求的 rid：hex(时间戳)-8位随机hex。"""
    ts = format(int(time.time()), "x")
    rnd = "".join(random.choice("0123456789abcdef") for _ in range(8))
    return "%s-%s" % (ts, rnd)


def _sph_post(url: str, headers: dict, payload: dict):
    """POST JSON，返回 (status, body)。"""
    req = urllib.request.Request(
        url, data=json.dumps(payload).encode("utf-8"), headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:
        raise RuntimeError("网络连接失败：%s" % e)


def _is_sph_url(url: str) -> bool:
    u = (url or "").lower()
    return "weixin.qq.com/sph/" in u or "channels.weixin.qq.com" in u


def _sph_token(url: str) -> str:
    m = re.search(r"weixin\.qq\.com/sph/([A-Za-z0-9_-]+)", url or "")
    if m:
        return m.group(1)
    raise ValueError("不是有效的视频号分享链接（需以 weixin.qq.com/sph/ 开头）")


def _sph_parse(url: str, cookie) -> dict:
    """第 1 步：元宝解析分享链接 → {export_id, general_token}。必须带元宝登录 Cookie。"""
    if not cookie:
        raise RuntimeError(
            "视频号解析需要「元宝」Cookie：请先在电脑浏览器登录 yuanbao.tencent.com，"
            "复制 Cookie 粘贴到 设置 Cookie → 视频号 后再提取")
    headers = dict(_SPH_PARSE_HEADERS)
    headers["cookie"] = cookie
    st, body = _sph_post(_SPH_PARSE_URL, headers,
                         {"type": "video_channel_url", "url": url, "scene": 1})
    if st != 200:
        raise RuntimeError(
            "视频号解析服务返回 %s：元宝 Cookie 可能已失效，"
            "请在浏览器重新登录 yuanbao.tencent.com 后更新 Cookie" % st)
    try:
        data = json.loads(body).get("data") or {}
    except Exception:
        raise RuntimeError("视频号解析失败：接口返回异常")
    if not data.get("wx_export_id"):
        raise RuntimeError("视频号解析失败：视频不存在、已删除或为私密内容（也可能元宝 Cookie 已失效）")
    export_id = ""
    general_token = ""
    try:
        pu = data.get("playable_url") or ""
        if pu.startswith("http"):
            q = parse_qs(urlparse(pu).query)
            general_token = (q.get("token") or [""])[0]
            export_id = (q.get("eid") or [""])[0]
    except Exception:
        pass
    if not export_id:
        export_id = data.get("wx_export_id") or ""
    return {"export_id": export_id, "general_token": general_token}


def _sph_feed(export_id: str, general_token: str):
    """第 2 步：取 feedInfo / authorInfo（含视频直链）。"""
    api = "%s?_rid=%s&_pageUrl=https%%3A%%2F%%2Fchannels.weixin.qq.com%%2Ffinder-preview%%2Fpages%%2Ffeed" % (
        _SPH_FEED_URL, _sph_rid())
    referer = (
        "https://channels.weixin.qq.com/finder-preview/pages/feed?entry_card_type=48&comment_scene=39&appid=0"
        "&token=%s&entry_scene=0&eid=%s" % (quote(general_token, safe=""), quote(export_id, safe="")))
    headers = dict(_SPH_FEED_HEADERS)
    headers["Referer"] = referer
    st, body = _sph_post(api, headers, {"baseReq": {"generalToken": general_token}, "exportId": export_id})
    if st != 200 and st != 201:
        raise RuntimeError("视频号接口返回 %s，请稍后重试" % st)
    try:
        data = json.loads(body).get("data") or {}
    except Exception:
        raise RuntimeError("视频号解析失败：接口返回异常")
    return data.get("feedInfo") or {}, data.get("authorInfo") or {}


def _sph_placeholder(url: str) -> bool:
    """上游对图片帖/直播回放会返回占位直链 finder.video.qq.com/N.mp4，点了必 404。"""
    try:
        u = urlparse(url or "")
        return u.hostname == "finder.video.qq.com" and re.match(r"^/\d+\.mp4$", u.path) is not None
    except Exception:
        return False


def _sph_pick_video(fi: dict) -> str:
    for key in ("h264VideoInfo", "h265VideoInfo"):
        v = (fi.get(key) or {}).get("videoUrl") or ""
        if v and not _sph_placeholder(v):
            return v
    v = fi.get("videoUrl") or ""
    if v and not _sph_placeholder(v):
        return v
    return ""


def _sph_info(url: str, cookie) -> dict:
    p = _sph_parse(url, cookie)
    fi, ai = _sph_feed(p["export_id"], p["general_token"])
    title = (fi.get("description") or "视频号视频").strip()[:60] or "视频号视频"
    return {
        "title": title,
        "description": (fi.get("description") or "").strip(),
        "duration": 0,
        "uploader": (ai.get("nickname") or "").strip(),
        "platform": "weixin_channels",
        "webpage_url": url,
        "video_url": _sph_pick_video(fi),
    }


def _sph_download(url: str, cookie, out_dir: str, progress_callback=None):
    """下载视频号视频（单文件 mp4），返回 (文件路径, info dict)。"""
    info = _sph_info(url, cookie)
    vurl = info["video_url"]
    if not vurl:
        raise RuntimeError("该视频号内容没有可下载的视频（图片帖、直播回放或已删除）")
    safe = re.sub(r'[\\/:*?"<>|]', "_", info["title"])[:80] or "视频号视频"
    path = os.path.join(out_dir, "%s.mp4" % safe)
    req = urllib.request.Request(vurl, headers={"User-Agent": _SPH_FEED_HEADERS["User-Agent"]})
    try:
        with urllib.request.urlopen(req, timeout=120) as resp, open(path, "wb") as f:
            total = 0
            try:
                total = int(resp.headers.get("Content-Length") or 0)
            except Exception:
                total = 0
            done = 0
            while True:
                chunk = resp.read(65536)
                if not chunk:
                    break
                f.write(chunk)
                done += len(chunk)
                if progress_callback is not None:
                    try:
                        progress_callback.onProgress(done, total, "")
                    except Exception:
                        pass
    except urllib.error.HTTPError as e:
        raise RuntimeError("视频下载失败：HTTP %s" % e.code)
    except Exception as e:
        raise RuntimeError("视频下载失败：%s" % e)
    if not os.path.exists(path) or os.path.getsize(path) == 0:
        raise RuntimeError("视频下载失败：文件为空")
    return path, info


# ================= 抖音图文（note）背景音乐提取 =================
# 抖音图文作品没有视频流，只有图片 + 背景音乐（原声）。yt-dlp 的 DouyinIE
# 只匹配 /video/<纯数字>，对 /note/ 图文直接报 Unsupported URL。
# 可行方案：分享短链 v.douyin.com/xxx 带移动端 UA 跟随跳转后，分享 URL 里带
# mid=<音乐ID>，再调抖音 music/detail 接口拿 music_info.play_url.url_list 直链（mp3）。
# 实测链路（2026-10）：短链跳转 → mid=7577766520676535078 → music/detail → mp3 直链可下载。

_DY_MUSIC_URL = "https://www.douyin.com/aweme/v1/web/music/detail/"

_DY_MOBILE_UA = ("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
                 "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1")


def _dy_resolve_short(url: str) -> str:
    """解析 v.douyin.com 短链：带移动端 UA 跟随跳转，返回最终分享 URL（含 mid 参数）。"""
    req = urllib.request.Request(url, headers={
        "User-Agent": _DY_MOBILE_UA,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    })
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.geturl()
    except urllib.error.HTTPError as e:
        if e.geturl():
            return e.geturl()
        raise RuntimeError("抖音短链解析失败：HTTP %s" % e.code)
    except Exception as e:
        raise RuntimeError("抖音短链解析失败：%s" % e)


def _dy_classify(url: str):
    """判断抖音链接类型：'note'（图文）/'video'（视频）/None（非抖音）。

    完整链接直接看路径；v.douyin.com 短链需跳转解析后再判断
    （短链既可能是视频也可能是图文，不能一概而论）。
    """
    u = (url or "").lower()
    if "douyin.com" not in u and "iesdouyin.com" not in u:
        return None
    if "v.douyin.com/" in u:
        try:
            final_url = _dy_resolve_short(url)
        except Exception:
            return "video"  # 跳转失败时交给 yt-dlp 走通用流程报错
        f = final_url.lower()
        if "/note/" in f or "share/note/" in f:
            return "note"
        return "video"
    if "/note/" in u or "share/note/" in u:
        return "note"
    return "video"


def _dy_music_id(url: str) -> str:
    """从图文链接中解析出音乐 ID（mid）。短链需先跳转，分享 URL 里带 mid 参数。"""
    u = (url or "").lower()
    final_url = url
    if "v.douyin.com/" in u:
        final_url = _dy_resolve_short(url)
    q = parse_qs(urlparse(final_url).query)
    mid = (q.get("mid") or [""])[0]
    if not mid:
        raise RuntimeError("该抖音图文没有可提取的背景音乐（分享链接未带音乐ID）")
    return mid


def _dy_music_info(mid: str, cookie) -> dict:
    """调 music/detail 接口，返回 {title, author, duration, url_list}。"""
    headers = {
        "User-Agent": _DY_MOBILE_UA,
        "Referer": "https://www.iesdouyin.com/",
        "Accept": "application/json, text/plain, */*",
    }
    if cookie:
        headers["Cookie"] = cookie
    api = "%s?music_id=%s&aid=1128&channel=channel_pc_web&device_platform=web&os=0&app_name=aweme" % (_DY_MUSIC_URL, mid)
    req = urllib.request.Request(api, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            body = resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        raise RuntimeError("抖音音乐接口返回 %s，请稍后重试" % e.code)
    except Exception as e:
        raise RuntimeError("网络连接失败：%s" % e)
    try:
        data = json.loads(body)
    except Exception:
        raise RuntimeError("抖音音乐解析失败：接口返回异常")
    mi = data.get("music_info") or {}
    if not mi:
        raise RuntimeError("未找到该图文的背景音乐（可能已下架或为私密内容）")
    urls = ((mi.get("play_url") or {}).get("url_list") or [])
    urls = [u for u in urls if u and u.startswith("http")]
    if not urls:
        raise RuntimeError("该背景音乐没有可下载的音频（可能受版权保护）")
    return {
        "title": (mi.get("title") or "抖音图文原声").strip(),
        "author": (mi.get("author") or "").strip(),
        "duration": mi.get("duration") or 0,
        "url_list": urls,
    }


def _dy_note_info(url: str, cookie) -> dict:
    mid = _dy_music_id(url)
    mi = _dy_music_info(mid, cookie)
    return {
        "title": mi["title"],
        "description": mi["title"],
        "duration": mi["duration"],
        "uploader": mi["author"],
        "platform": "douyin_note",
        "webpage_url": url,
        "music_url": mi["url_list"][0],
    }


def _dy_note_download(url: str, cookie, out_dir: str, progress_callback=None):
    """下载抖音图文背景音乐（mp3），返回 (文件路径, info dict)。"""
    info = _dy_note_info(url, cookie)
    safe = re.sub(r'[\\/:*?"<>|]', "_", info["title"])[:80] or "抖音图文原声"
    path = os.path.join(out_dir, "%s.mp3" % safe)
    req = urllib.request.Request(info["music_url"], headers={"User-Agent": _DY_MOBILE_UA})
    try:
        with urllib.request.urlopen(req, timeout=120) as resp, open(path, "wb") as f:
            total = 0
            try:
                total = int(resp.headers.get("Content-Length") or 0)
            except Exception:
                total = 0
            done = 0
            while True:
                chunk = resp.read(65536)
                if not chunk:
                    break
                f.write(chunk)
                done += len(chunk)
                if progress_callback is not None:
                    try:
                        progress_callback.onProgress(done, total, "")
                    except Exception:
                        pass
    except urllib.error.HTTPError as e:
        raise RuntimeError("音频下载失败：HTTP %s" % e.code)
    except Exception as e:
        raise RuntimeError("音频下载失败：%s" % e)
    if not os.path.exists(path) or os.path.getsize(path) == 0:
        raise RuntimeError("音频下载失败：文件为空")
    return path, info


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
    # 视频号：走元宝两段式解析（不走 yt-dlp）
    if _is_sph_url(url):
        try:
            info = _sph_info(url, cookie)
        except Exception as e:
            return json.dumps({"error": _friendly(e)}, ensure_ascii=False)
        return json.dumps({
            "title": info["title"],
            "description": info["description"],
            "duration": 0,
            "uploader": info["uploader"],
            "platform": "weixin_channels",
            "webpage_url": url,
        }, ensure_ascii=False)
    # 抖音图文：提取背景音乐信息（不走 yt-dlp）
    if _dy_classify(url) == "note":
        try:
            info = _dy_note_info(url, cookie)
        except Exception as e:
            return json.dumps({"error": _friendly(e)}, ensure_ascii=False)
        return json.dumps({
            "title": info["title"],
            "description": info["description"],
            "duration": info["duration"],
            "uploader": info["uploader"],
            "platform": "douyin_note",
            "webpage_url": url,
        }, ensure_ascii=False)
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

    # 视频号：走元宝两段式解析 + 直链下载（不走 yt-dlp）
    if _is_sph_url(url):
        if mode == "video":
            try:
                path, info = _sph_download(url, cookie, out_dir, progress_callback)
            except Exception as e:
                return json.dumps({"error": _friendly(e)}, ensure_ascii=False)
            return json.dumps({
                "path": path,
                "title": info["title"],
                "ext": "mp4",
            }, ensure_ascii=False)
        elif mode == "audio":
            return json.dumps({"error": "视频号暂不支持直接提取音频，请用「视频」模式下载后再用其他工具转音频"}, ensure_ascii=False)
        else:
            return json.dumps({"error": "未知提取模式"}, ensure_ascii=False)

    # 抖音图文：提取背景音乐（mp3），仅音频模式支持
    if _dy_classify(url) == "note":
        if mode == "audio":
            try:
                path, info = _dy_note_download(url, cookie, out_dir, progress_callback)
            except Exception as e:
                return json.dumps({"error": _friendly(e)}, ensure_ascii=False)
            return json.dumps({
                "path": path,
                "title": info["title"],
                "ext": "mp3",
            }, ensure_ascii=False)
        elif mode == "video":
            return json.dumps({"error": "抖音图文没有视频画面，请用「音频」模式提取背景音乐"}, ensure_ascii=False)
        else:
            return json.dumps({"error": "未知提取模式"}, ensure_ascii=False)

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
