/*
 * Copyright (c) 2026 Jingge Chen <mariocanfly@hotmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.file

import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.provider.common.readAllBytes
import me.zhanghai.android.files.provider.common.readAttributes

/**
 * Windows 快捷方式（.lnk，MS-SHLLINK 二进制格式）的解析与目标路径解析。
 *
 * 快捷方式里记录的是 Windows 路径，需要映射到本应用的文件系统：
 * 1. 相对路径（RELATIVE_PATH）相对 lnk 所在目录解析，同一目录树内最可靠；
 * 2. 绝对路径（盘符路径 C:\... 或 UNC 路径 \\server\share\...）去掉盘符和开头斜杠后，
 *    再依次尝试挂到当前文件系统根目录和 lnk 所在目录下。
 * 所有候选路径都不存在时返回 null。
 */
object WindowsShortcut {
    private const val HEADER_SIZE = 76
    private const val HEADER_SIZE_VALUE = 0x0000004C
    private const val FLAG_HAS_LINK_TARGET_ID_LIST = 0x00000001
    private const val FLAG_HAS_LINK_INFO = 0x00000002
    private const val FLAG_HAS_NAME = 0x00000004
    private const val FLAG_HAS_RELATIVE_PATH = 0x00000008
    private const val FLAG_IS_UNICODE = 0x00000080
    private const val LINK_INFO_FLAG_VOLUME_ID_AND_LOCAL_BASE_PATH = 0x00000001
    private const val LINK_INFO_FLAG_COMMON_NETWORK_RELATIVE_LINK_AND_PATH_SUFFIX = 0x00000002
    private const val LINK_INFO_HEADER_SIZE_WITH_UNICODE = 0x00000024
    private const val MAX_BYTES = 1 shl 20

    private class ParsedShortcut(
        val localBasePath: String?,
        val networkPath: String?,
        val relativePath: String?
    )

    /**
     * 解析 lnk 指向的目标路径，返回在本应用文件系统中确实存在的那一个；解析失败返回 null。
     */
    fun resolve(shortcutPath: Path): Path? {
        val parsed = try {
            val bytes = shortcutPath.readAllBytes()
            if (bytes.size > MAX_BYTES) {
                null
            } else {
                parse(bytes)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } ?: return null
        for (candidate in candidatePaths(shortcutPath, parsed)) {
            if (exists(candidate)) {
                return candidate
            }
        }
        return null
    }

    private fun candidatePaths(shortcutPath: Path, parsed: ParsedShortcut): List<Path> {
        val parent = shortcutPath.parent
        val root = shortcutPath.root
        val candidates = mutableListOf<Path>()
        if (parent != null) {
            parsed.relativePath?.let { resolveSegments(parent, it)?.let(candidates::add) }
        }
        for (absolutePath in listOfNotNull(parsed.localBasePath, parsed.networkPath)) {
            val strippedPath = stripRootPrefix(absolutePath)
            if (strippedPath.isEmpty()) {
                continue
            }
            if (root != null) {
                resolveSegments(root, strippedPath)?.let(candidates::add)
            }
            if (parent != null) {
                resolveSegments(parent, strippedPath)?.let(candidates::add)
            }
        }
        return candidates
    }

    /** 把 Windows 路径片段逐个拼到 [base] 上，支持 "." 与 ".."。 */
    private fun resolveSegments(base: Path, rawPath: String): Path? {
        var path = base
        for (segment in rawPath.replace('\\', '/').split('/')) {
            when (segment) {
                "", "." -> {}
                ".." -> path = path.parent ?: return null
                else -> path = path.resolve(segment)
            }
        }
        return path
    }

    /** 去掉盘符（C:）和开头的斜杠，得到可挂到根目录下的相对路径。 */
    private fun stripRootPrefix(rawPath: String): String {
        var path = rawPath.replace('\\', '/')
        if (path.length >= 2 && path[1] == ':') {
            path = path.substring(2)
        }
        return path.trimStart('/')
    }

    private fun exists(path: Path): Boolean = try {
        path.readAttributes(BasicFileAttributes::class.java)
        true
    } catch (e: Exception) {
        false
    }

    private fun parse(bytes: ByteArray): ParsedShortcut? {
        if (bytes.size < HEADER_SIZE || bytes.u32(0) != HEADER_SIZE_VALUE) {
            return null
        }
        val linkFlags = bytes.u32(20)
        var offset = HEADER_SIZE
        if (linkFlags and FLAG_HAS_LINK_TARGET_ID_LIST != 0) {
            if (offset + 2 > bytes.size) {
                return null
            }
            offset += 2 + bytes.u16(offset)
        }
        var localBasePath: String? = null
        var networkPath: String? = null
        if (linkFlags and FLAG_HAS_LINK_INFO != 0) {
            if (offset + 4 > bytes.size) {
                return null
            }
            val linkInfoSize = bytes.u32(offset)
            if (linkInfoSize < 4 || offset + linkInfoSize > bytes.size) {
                return null
            }
            val linkInfoStart = offset
            val linkInfoEnd = offset + linkInfoSize
            val linkInfoHeaderSize = bytes.u32(offset + 4)
            val linkInfoFlags = bytes.u32(offset + 8)
            val localBasePathOffset = bytes.u32(offset + 16)
            val commonPathSuffixOffset = bytes.u32(offset + 28)
            var localBasePathUnicodeOffset = 0
            var commonPathSuffixUnicodeOffset = 0
            if (linkInfoHeaderSize >= LINK_INFO_HEADER_SIZE_WITH_UNICODE
                && offset + 40 <= bytes.size) {
                localBasePathUnicodeOffset = bytes.u32(offset + 32)
                commonPathSuffixUnicodeOffset = bytes.u32(offset + 36)
            }
            val commonPathSuffix = if (commonPathSuffixUnicodeOffset != 0) {
                readUnicodeString(bytes, linkInfoStart + commonPathSuffixUnicodeOffset, linkInfoEnd)
            } else if (commonPathSuffixOffset != 0) {
                readAnsiString(bytes, linkInfoStart + commonPathSuffixOffset, linkInfoEnd)
            } else {
                null
            }
            if (linkInfoFlags and LINK_INFO_FLAG_VOLUME_ID_AND_LOCAL_BASE_PATH != 0) {
                val basePath = if (localBasePathUnicodeOffset != 0) {
                    readUnicodeString(bytes, linkInfoStart + localBasePathUnicodeOffset, linkInfoEnd)
                } else if (localBasePathOffset != 0) {
                    readAnsiString(bytes, linkInfoStart + localBasePathOffset, linkInfoEnd)
                } else {
                    null
                }
                if (basePath != null) {
                    localBasePath = basePath + (commonPathSuffix ?: "")
                }
            }
            if (linkInfoFlags
                and LINK_INFO_FLAG_COMMON_NETWORK_RELATIVE_LINK_AND_PATH_SUFFIX != 0) {
                val networkLinkOffset = bytes.u32(offset + 24)
                val networkLinkStart = linkInfoStart + networkLinkOffset
                if (networkLinkOffset != 0 && networkLinkStart + 12 <= bytes.size) {
                    val netNameOffset = bytes.u32(networkLinkStart + 8)
                    if (netNameOffset != 0) {
                        val netName = readAnsiString(
                            bytes, networkLinkStart + netNameOffset, linkInfoEnd
                        )
                        if (netName != null) {
                            networkPath = netName + (commonPathSuffix ?: "")
                        }
                    }
                }
            }
            offset = linkInfoEnd
        }
        // StringData 按固定顺序存放，只读我们需要的 NAME_STRING 与 RELATIVE_PATH
        val isUnicode = linkFlags and FLAG_IS_UNICODE != 0
        if (linkFlags and FLAG_HAS_NAME != 0) {
            offset = readStringData(bytes, offset, isUnicode).second
        }
        var relativePath: String? = null
        if (linkFlags and FLAG_HAS_RELATIVE_PATH != 0) {
            val (value, nextOffset) = readStringData(bytes, offset, isUnicode)
            relativePath = value
            offset = nextOffset
        }
        return ParsedShortcut(localBasePath, networkPath, relativePath)
    }

    /** 读取 StringData：2 字节字符数 + 字符数据。 */
    private fun readStringData(
        bytes: ByteArray,
        offset: Int,
        isUnicode: Boolean
    ): Pair<String?, Int> {
        if (offset + 2 > bytes.size) {
            return null to bytes.size
        }
        val count = bytes.u16(offset)
        val charSize = if (isUnicode) 2 else 1
        val length = count * charSize
        val start = offset + 2
        if (length < 0 || start + length > bytes.size) {
            return null to bytes.size
        }
        val value = if (isUnicode) {
            String(bytes, start, length, Charsets.UTF_16LE)
        } else {
            String(bytes, start, length, Charsets.ISO_8859_1)
        }
        return value to (start + length)
    }

    private fun readAnsiString(bytes: ByteArray, start: Int, end: Int): String? {
        if (start < 0 || start >= bytes.size) {
            return null
        }
        val limit = minOf(end, bytes.size)
        var stop = start
        while (stop < limit && bytes[stop].toInt() != 0) {
            ++stop
        }
        return String(bytes, start, stop - start, Charsets.ISO_8859_1)
    }

    private fun readUnicodeString(bytes: ByteArray, start: Int, end: Int): String? {
        if (start < 0 || start >= bytes.size) {
            return null
        }
        val limit = minOf(end, bytes.size)
        var stop = start
        while (stop + 1 < limit
            && !(bytes[stop].toInt() == 0 && bytes[stop + 1].toInt() == 0)) {
            stop += 2
        }
        return String(bytes, start, stop - start, Charsets.UTF_16LE)
    }

    private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xFF

    private fun ByteArray.u16(offset: Int): Int = u8(offset) or (u8(offset + 1) shl 8)

    private fun ByteArray.u32(offset: Int): Int = u16(offset) or (u16(offset + 2) shl 16)
}
