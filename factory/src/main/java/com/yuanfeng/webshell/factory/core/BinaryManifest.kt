package com.yuanfeng.webshell.factory.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 读取并改写 APK 中「编译后」的 AndroidManifest.xml（二进制 XML，俗称 AXML）。
 *
 * APK 里的 Manifest 不是文本 XML，而是 AAPT2 编译出的二进制格式：
 *
 *   ResXMLTree_header (type=0x0003)
 *   ├── ResStringPool (type=0x0001)      所有字符串（元素名、属性名、属性字符串值）集中存放，其余部分按下标引用
 *   ├── ResourceMap  (type=0x0180)       前 N 个字符串（属性名）对应的系统资源 ID，如 android:label = 0x01010001
 *   └── XML 节点序列                       START_NAMESPACE / START_ELEMENT / END_ELEMENT ……
 *        每个属性 20 字节：ns, name, rawValue(字符串下标), typedValue{size, res0, dataType, data}
 *
 * 我们只需要三种修改，都不改变「节点部分」的结构和长度：
 * 1. 按前缀替换字符串池里的字符串（包名及由包名派生的 authority / permission 名）；
 * 2. 把某个属性改成新的字符串：在字符串池末尾追加新字符串，再让属性指向它的下标；
 * 3. 改整数 / 布尔属性：直接改属性的 data 字段。
 * 所以只需重新编码字符串池，节点部分原样拷贝即可。
 *
 * 不依赖任何 Android API，可以在电脑上做单元测试。
 */
class BinaryManifest(bytes: ByteArray) {

    /** 字符串池内容；下标即 AXML 中引用字符串所用的编号 */
    val strings: MutableList<String>

    private val poolHeaderSize: Int
    private val poolFlags: Int
    private val isUtf8: Boolean

    /** 字符串池之后的全部字节（资源映射 + 节点），可原地修改属性值 */
    private val rest: ByteArray
    private val resourceIds: IntArray
    private val attributes: List<Attribute>

    /** 属性在 rest 中的位置及其所属元素 */
    private class Attribute(val element: String, val nameIndex: Int, val offset: Int)

    init {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(buf.getShort(0).toInt() == RES_XML_TYPE) { "不是二进制 XML（文件头类型 ${buf.getShort(0)}）" }

        // ---- 字符串池 ----
        val pool = buf.getShort(2).toInt() and 0xFFFF // 文件头长度，通常为 8
        require(buf.getShort(pool).toInt() == RES_STRING_POOL_TYPE) { "XML 头之后不是字符串池" }
        poolHeaderSize = buf.getShort(pool + 2).toInt() and 0xFFFF
        val poolSize = buf.getInt(pool + 4)
        val stringCount = buf.getInt(pool + 8)
        val styleCount = buf.getInt(pool + 12)
        poolFlags = buf.getInt(pool + 16)
        val stringsStart = buf.getInt(pool + 20)
        require(styleCount == 0) { "不支持带样式的字符串池（styleCount=$styleCount）" }
        isUtf8 = (poolFlags and FLAG_UTF8) != 0

        strings = MutableList(stringCount) { i ->
            val offset = pool + stringsStart + buf.getInt(pool + poolHeaderSize + i * 4)
            if (isUtf8) decodeUtf8(buf, offset) else decodeUtf16(buf, offset)
        }

        // ---- 其余部分 ----
        val restStart = pool + poolSize
        rest = bytes.copyOfRange(restStart, bytes.size)
        val r = ByteBuffer.wrap(rest).order(ByteOrder.LITTLE_ENDIAN)
        var ids = IntArray(0)
        val attrs = mutableListOf<Attribute>()
        var pos = 0
        while (pos + 8 <= rest.size) {
            val type = r.getShort(pos).toInt() and 0xFFFF
            val headerSize = r.getShort(pos + 2).toInt() and 0xFFFF
            val size = r.getInt(pos + 4)
            require(size >= 8 && pos + size <= rest.size) { "AXML 节点长度异常：offset=$pos size=$size" }
            when (type) {
                RES_XML_RESOURCE_MAP_TYPE -> ids = IntArray((size - headerSize) / 4) { r.getInt(pos + headerSize + it * 4) }
                RES_XML_START_ELEMENT_TYPE -> {
                    val ext = pos + headerSize
                    val element = strings[r.getInt(ext + 4)]
                    val attrStart = r.getShort(ext + 8).toInt() and 0xFFFF
                    val attrSize = r.getShort(ext + 10).toInt() and 0xFFFF
                    val attrCount = r.getShort(ext + 12).toInt() and 0xFFFF
                    for (i in 0 until attrCount) {
                        val a = ext + attrStart + i * attrSize
                        attrs += Attribute(element, r.getInt(a + 4), a)
                    }
                }
            }
            pos += size
        }
        resourceIds = ids
        attributes = attrs
    }

    // ------------------------------------------------------------------
    // 查询（主要给单元测试和校验用）
    // ------------------------------------------------------------------

    fun getString(element: String, attrResId: Int): String? {
        val a = find(element, attrResId) ?: return null
        val raw = ByteBuffer.wrap(rest).order(ByteOrder.LITTLE_ENDIAN).getInt(a.offset + 8)
        return if (raw >= 0) strings[raw] else null
    }

    fun getInt(element: String, attrResId: Int): Int? {
        val a = find(element, attrResId) ?: return null
        return ByteBuffer.wrap(rest).order(ByteOrder.LITTLE_ENDIAN).getInt(a.offset + 16)
    }

    /** 非 android: 命名空间的属性（如 <manifest package="...">）按属性名查找 */
    fun getPlainString(element: String, attrName: String): String? {
        val r = ByteBuffer.wrap(rest).order(ByteOrder.LITTLE_ENDIAN)
        val a = attributes.firstOrNull { it.element == element && strings[it.nameIndex] == attrName } ?: return null
        val raw = r.getInt(a.offset + 8)
        return if (raw >= 0) strings[raw] else null
    }

    // ------------------------------------------------------------------
    // 修改
    // ------------------------------------------------------------------

    /** 把所有以 [oldPrefix] 开头的字符串替换成以 [newPrefix] 开头，返回替换个数 */
    fun replacePrefix(oldPrefix: String, newPrefix: String): Int {
        var count = 0
        for (i in strings.indices) {
            if (strings[i].startsWith(oldPrefix)) {
                strings[i] = newPrefix + strings[i].substring(oldPrefix.length)
                count++
            }
        }
        return count
    }

    /** 把 element 上的 android 属性改成新字符串（追加到字符串池末尾，不影响其他引用同一字符串的地方） */
    fun setString(element: String, attrResId: Int, value: String) {
        val a = requireNotNull(find(element, attrResId)) { "<$element> 上没有属性 0x${attrResId.toString(16)}" }
        val index = strings.size
        strings += value
        ByteBuffer.wrap(rest).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(a.offset + 8, index)                // rawValue
            putShort(a.offset + 12, 8)                 // typedValue.size
            put(a.offset + 14, 0)                      // res0
            put(a.offset + 15, TYPE_STRING.toByte())   // dataType
            putInt(a.offset + 16, index)               // data
        }
    }

    fun setInt(element: String, attrResId: Int, value: Int) = setTyped(element, attrResId, TYPE_INT_DEC, value)

    fun setBoolean(element: String, attrResId: Int, value: Boolean) =
        setTyped(element, attrResId, TYPE_INT_BOOLEAN, if (value) -1 else 0)

    private fun setTyped(element: String, attrResId: Int, dataType: Int, data: Int) {
        val a = requireNotNull(find(element, attrResId)) { "<$element> 上没有属性 0x${attrResId.toString(16)}" }
        ByteBuffer.wrap(rest).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(a.offset + 8, -1) // 非字符串属性没有 rawValue
            putShort(a.offset + 12, 8)
            put(a.offset + 14, 0)
            put(a.offset + 15, dataType.toByte())
            putInt(a.offset + 16, data)
        }
    }

    private fun find(element: String, attrResId: Int): Attribute? = attributes.firstOrNull {
        it.element == element && it.nameIndex < resourceIds.size && resourceIds[it.nameIndex] == attrResId
    }

    // ------------------------------------------------------------------
    // 序列化
    // ------------------------------------------------------------------

    fun toByteArray(): ByteArray {
        val pool = encodePool()
        val total = 8 + pool.size + rest.size
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(RES_XML_TYPE.toShort())
        out.putShort(8)
        out.putInt(total)
        out.put(pool)
        out.put(rest)
        return out.array()
    }

    private fun encodePool(): ByteArray {
        val data = ByteArrayOutputStream()
        val offsets = IntArray(strings.size)
        for ((i, s) in strings.withIndex()) {
            offsets[i] = data.size()
            data.write(if (isUtf8) encodeUtf8(s) else encodeUtf16(s))
        }
        while (data.size() % 4 != 0) data.write(0) // 每个 chunk 必须 4 字节对齐

        val stringsStart = poolHeaderSize + strings.size * 4
        val size = stringsStart + data.size()
        val out = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(RES_STRING_POOL_TYPE.toShort())
        out.putShort(poolHeaderSize.toShort())
        out.putInt(size)
        out.putInt(strings.size)
        out.putInt(0)                                // styleCount
        out.putInt(poolFlags and FLAG_SORTED.inv())  // 追加字符串后不再有序
        out.putInt(stringsStart)
        out.putInt(0)                                // stylesStart
        out.position(poolHeaderSize)
        offsets.forEach { out.putInt(it) }
        out.put(data.toByteArray())
        return out.array()
    }

    companion object {
        // 系统属性的资源 ID（见 android.R.attr）
        const val ATTR_LABEL = 0x01010001
        const val ATTR_VERSION_CODE = 0x0101021b
        const val ATTR_VERSION_NAME = 0x0101021c
        const val ATTR_USES_CLEARTEXT_TRAFFIC = 0x010104ec

        private const val RES_STRING_POOL_TYPE = 0x0001
        private const val RES_XML_TYPE = 0x0003
        private const val RES_XML_START_ELEMENT_TYPE = 0x0102
        private const val RES_XML_RESOURCE_MAP_TYPE = 0x0180
        private const val FLAG_SORTED = 0x1
        private const val FLAG_UTF8 = 0x100
        private const val TYPE_STRING = 0x03
        private const val TYPE_INT_DEC = 0x10
        private const val TYPE_INT_BOOLEAN = 0x12

        // UTF-8 字符串格式：[UTF-16 长度][UTF-8 字节长度][字节...][0]，两个长度各占 1~2 字节
        private fun decodeUtf8(buf: ByteBuffer, start: Int): String {
            var p = start
            fun len(): Int {
                val b0 = buf.get(p++).toInt() and 0xFF
                return if (b0 and 0x80 != 0) ((b0 and 0x7F) shl 8) or (buf.get(p++).toInt() and 0xFF) else b0
            }
            len() // UTF-16 长度，解码时用不到
            val n = len()
            val bytes = ByteArray(n) { buf.get(p + it) }
            return String(bytes, Charsets.UTF_8)
        }

        private fun encodeUtf8(s: String): ByteArray {
            val bytes = s.toByteArray(Charsets.UTF_8)
            val out = ByteArrayOutputStream()
            fun len(n: Int) {
                require(n <= 0x7FFF) { "字符串过长：$n" }
                if (n > 0x7F) { out.write(0x80 or (n shr 8)); out.write(n and 0xFF) } else out.write(n)
            }
            len(s.length)
            len(bytes.size)
            out.write(bytes)
            out.write(0)
            return out.toByteArray()
        }

        // UTF-16 字符串格式：[长度(u16，超过 0x7FFF 时占两个 u16)][UTF-16LE 字符...][0x0000]
        private fun decodeUtf16(buf: ByteBuffer, start: Int): String {
            var p = start
            var n = buf.getShort(p).toInt() and 0xFFFF
            p += 2
            if (n and 0x8000 != 0) {
                n = ((n and 0x7FFF) shl 16) or (buf.getShort(p).toInt() and 0xFFFF)
                p += 2
            }
            val chars = CharArray(n) { buf.getChar(p + it * 2) }
            return String(chars)
        }

        private fun encodeUtf16(s: String): ByteArray {
            val n = s.length
            val header = if (n > 0x7FFF) 4 else 2
            val out = ByteBuffer.allocate(header + n * 2 + 2).order(ByteOrder.LITTLE_ENDIAN)
            if (n > 0x7FFF) {
                out.putShort((0x8000 or (n shr 16)).toShort())
                out.putShort((n and 0xFFFF).toShort())
            } else {
                out.putShort(n.toShort())
            }
            s.forEach { out.putChar(it) }
            out.putShort(0)
            return out.array()
        }
    }
}
