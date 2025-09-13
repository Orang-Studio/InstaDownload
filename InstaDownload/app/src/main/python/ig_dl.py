# ig_dl.py
import re
import instaloader

_SC_REGEX = re.compile(r"instagram\.com/(?:reel|p|tv)/([A-Za-z0-9_-]+)")

def _shortcode_from_url(url: str):
    m = _SC_REGEX.search(url)
    return m.group(1) if m else None

def direct_video_url(url: str, username: str=None, password: str=None):
    """
    Returns a single direct video URL (str) if the post is a video or a carousel with any video.
    Returns None if no video is present (i.e., images only).
    """
    sc = _shortcode_from_url(url)
    if not sc:
        raise ValueError("Invalid Instagram URL")

    L = instaloader.Instaloader(
        download_comments=False,
        save_metadata=False,
        post_metadata_txt_pattern=""
    )
    if username and password:
        L.login(username, password)

    post = instaloader.Post.from_shortcode(L.context, sc)

    if post.is_video:
        return post.video_url

    # Look for a video in sidecar (carousel)
    for node in post.get_sidecar_nodes():
        if node.is_video:
            return node.video_url

    # No video in this post
    return None

def all_media_urls(url: str, username: str=None, password: str=None):
    """
    Returns a list of all media URLs (videos first) for convenience if you ever want images too.
    """
    sc = _shortcode_from_url(url)
    if not sc:
        raise ValueError("Invalid Instagram URL")

    L = instaloader.Instaloader(
        download_comments=False,
        save_metadata=False,
        post_metadata_txt_pattern=""
    )
    if username and password:
        L.login(username, password)

    post = instaloader.Post.from_shortcode(L.context, sc)
    urls = []
    if post.is_video:
        urls.append(post.video_url)
    else:
        urls.append(post.url)

    for node in post.get_sidecar_nodes():
        urls.append(node.video_url if node.is_video else node.display_url)

    return urls
