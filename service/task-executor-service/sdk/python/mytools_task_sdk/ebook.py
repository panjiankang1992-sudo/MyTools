"""Shared bounded ebook parsing primitives for task scripts."""

from __future__ import annotations

from pathlib import PurePosixPath
import urllib.parse
import xml.etree.ElementTree as element_tree
import zipfile


def decode_text(data: bytes) -> str:
    """Decode strict UTF-8 first and fall back to common legacy Chinese encoding."""
    try:
        return data.decode("utf-8-sig")
    except UnicodeDecodeError:
        return data.decode("gb18030", errors="replace")


def safe_zip_name(parent: str, href: str) -> str:
    """Resolve an archive href without allowing absolute or traversal paths."""
    if not href or "\\" in href:
        return ""
    decoded = urllib.parse.unquote(href)
    path = PurePosixPath(parent).parent.joinpath(decoded)
    parts = []
    for part in path.parts:
        if part in {"", "."}:
            continue
        if part == "..":
            if not parts:
                return ""
            parts.pop()
        else:
            parts.append(part)
    return "/".join(parts)


def validate_archive(archive: zipfile.ZipFile, maximum_entries: int,
                     maximum_expanded_bytes: int, maximum_ratio: int = 1000) -> None:
    """Reject oversized archives, duplicate names, and suspicious compression ratios."""
    infos = archive.infolist()
    if len(infos) > maximum_entries:
        raise ValueError("Archive entry count exceeds limit")
    expanded = 0
    names = set()
    for info in infos:
        if info.filename in names:
            raise ValueError("Archive contains duplicate names")
        names.add(info.filename)
        expanded += max(0, info.file_size)
        if expanded > maximum_expanded_bytes:
            raise ValueError("Archive expanded size exceeds limit")
        if info.compress_size > 0 and info.file_size > info.compress_size * maximum_ratio:
            raise ValueError("Archive compression ratio exceeds limit")


def read_zip_entry(archive: zipfile.ZipFile, name: str, limit: int) -> bytes:
    """Read one regular bounded archive entry."""
    info = archive.getinfo(name)
    if info.is_dir() or info.file_size > limit:
        raise ValueError("Archive entry is invalid or too large")
    with archive.open(info) as source:
        content = source.read(limit + 1)
    if len(content) > limit:
        raise ValueError("Archive entry exceeds limit")
    return content


def local_name(element: element_tree.Element) -> str:
    """Return an XML element local name without its namespace."""
    return element.tag.rsplit("}", 1)[-1]


def first_local_text(root: element_tree.Element, name: str) -> str:
    """Return the first nonblank XML element text matching a local name."""
    for item in root.iter():
        if local_name(item) == name and item.text and item.text.strip():
            return item.text.strip()
    return ""
