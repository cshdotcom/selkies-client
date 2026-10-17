package com.selkies.client.platform

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * v2.7.5 SAF（Storage Access Framework）路径工具：
 * - tree URI ↔ 真实绝对路径转换（主存储 → /storage/emulated/0/…；SD/USB 卷 → /storage/XXXX-YYYY/…）
 * - 用户选择「默认存储位置」后，Android 经 SAF 拿到的是 content:// tree URI，
 *   用户认知的是真实绝对路径——此处负责两者互转（展示用绝对路径、写入用 tree URI）
 *
 * SAF 子目录布局：{所选根}/Selkies/{Screenshots|Recordings|Downloads}
 */
object SafPaths {

    const val TOP_DIR = "Selkies"
    const val SUB_SCREENSHOTS = "Screenshots"
    const val SUB_RECORDINGS = "Recordings"
    const val SUB_DOWNLOADS = "Downloads"

    /** 纯解析：DocumentsContract 树 docId → 绝对路径（不触碰 Android API，便于单测）
     *  docId 形如 "primary:Foo/Bar"（主存储）或 "XXXX-YYYY:Foo/Bar"（SD/USB 卷）。
     *  【v2.7.7】选根目录（"primary:"/"XXXX-YYYY:"，path 为空）→ 返回卷根绝对路径（旧版返 null
     *  → 展示回退 "SAF:primary:"，用户无法认知选的哪里） */
    fun docIdToAbsolutePath(docId: String, primaryRoot: String): String? {
        val idx = docId.indexOf(':')
        if (idx <= 0) return null
        val volume = docId.substring(0, idx)
        val path = docId.substring(idx + 1).trimEnd('/')
        val volumeRoot = when (volume) {
            "primary" -> primaryRoot
            else -> "/storage/$volume"
        }
        if (path.isEmpty()) return volumeRoot
        return "$volumeRoot/$path"
    }

    /** tree URI → 展示用真实绝对路径；解析不出时回退可读描述（防误导用户） */
    fun resolveDisplayPath(ctx: Context, treeUri: Uri): String {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return readableFallback(treeUri)
        val primaryRoot = runCatching {
            @Suppress("DEPRECATION")
            Environment2.externalStorageDir() ?: "/storage/emulated/0"
        }.getOrDefault("/storage/emulated/0")
        val abs = docIdToAbsolutePath(docId, primaryRoot) ?: return readableFallback(treeUri)
        // 能访问到该路径则直接返回（主存储绝大多数场景成立）
        return abs
    }

    private fun readableFallback(treeUri: Uri): String {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: ""
        return if (docId.isNotBlank()) "SAF:$docId" else treeUri.toString()
    }

    /** 文件 docId（"primary:Selkies/Screenshots/x.png"）→ 绝对路径（列表/保存后展示） */
    fun fileDocIdToAbsolutePath(ctx: Context, fileDocId: String): String? {
        val primaryRoot = runCatching {
            @Suppress("DEPRECATION")
            Environment2.externalStorageDir() ?: "/storage/emulated/0"
        }.getOrDefault("/storage/emulated/0")
        return docIdToAbsolutePath(fileDocId, primaryRoot)
    }

    /** 环境包装（隔离 android.os.Environment 便于定位） */
    private object Environment2 {
        fun externalStorageDir(): String? =
            runCatching { android.os.Environment.getExternalStorageDirectory()?.absolutePath }.getOrNull()
    }

    // ---------------- SAF 文档操作 ----------------

    /** tree URI → 该目录树的「根文档」URI（可对其 createDocument / 查询子项） */
    fun treeRootDocUri(treeUri: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri)
        )

    /** 在 parent 下查找名为 name 的子目录；不存在则创建。返回其文档 URI（失败 null） */
    fun findOrCreateDir(ctx: Context, parent: Uri, name: String): Uri? {
        findChildDir(ctx, parent, name)?.let { return it }
        return runCatching {
            DocumentsContract.createDocument(
                ctx.contentResolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name
            )
        }.getOrNull()
    }

    /** 查找名为 name 的子目录（仅一层）；找不到 null */
    fun findChildDir(ctx: Context, parent: Uri, name: String): Uri? {
        val children = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(
                parent, DocumentsContract.getDocumentId(parent)
            )
        }.getOrNull() ?: return null
        return runCatching {
            ctx.contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val mime = c.getString(2) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR &&
                        c.getString(1) == name
                    ) {
                        return@use DocumentsContract.buildDocumentUriUsingTree(parent, c.getString(0))
                    }
                }
                null
            }
        }.getOrNull()
    }

    /** 列出 parent 下全部文件（非目录）文档：docId / 名称 / 大小 / 修改时间 */
    fun listFiles(ctx: Context, parent: Uri): List<SafFileRow> {
        val out = ArrayList<SafFileRow>()
        val children = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(
                parent, DocumentsContract.getDocumentId(parent)
            )
        }.getOrNull() ?: return out
        runCatching {
            ctx.contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val mime = c.getString(4) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    out.add(
                        SafFileRow(
                            documentId = c.getString(0),
                            displayName = c.getString(1) ?: "",
                            sizeBytes = c.getLong(2),
                            modifiedAt = c.getLong(3)
                        )
                    )
                }
            }
        }
        return out
    }

    data class SafFileRow(
        val documentId: String,
        val displayName: String,
        val sizeBytes: Long,
        val modifiedAt: Long
    )
}
