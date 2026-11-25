/*
 * Copyright (C) 2026 Hleb Kukleuski <hlebkukleuski@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://gnu.org>.
 */

package me.hlebkukleuski.fullfilestudio.feature.gpl.blend

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

internal class BlendPatcher(raw: ByteArray) {
    private var bytes = BlendFile.decompress(raw)
    private val file get() = BlendFile(bytes)

    class Fill(private val f: BlendFile, private val s: BlendFile.Struct, private val buf: ByteBuffer) {
        private fun field(path: String): Pair<Int, BlendFile.Field> {
            var st = s
            var off = 0
            val parts = path.split('.')
            for ((i, p) in parts.withIndex()) {
                val fd = st.fields[p] ?: error("no field $p in ${st.type}")
                off += fd.offset
                if (i == parts.lastIndex) return off to fd
                st = f.struct(fd.type) ?: error("no struct ${fd.type}")
            }
            error(path)
        }
        fun has(path: String) = runCatching { field(path) }.isSuccess
        fun int(path: String, v: Int) {
            val (o, fd) = field(path)
            when (fd.size) {
                1 -> buf.put(o, v.toByte())
                2 -> buf.putShort(o, v.toShort())
                else -> buf.putInt(o, v)
            }
        }
        fun float(path: String, v: Float) = field(path).let { (o, _) -> buf.putFloat(o, v) }
        fun floats(path: String, v: FloatArray) = field(path).let { (o, _) -> v.forEachIndexed { i, x -> buf.putFloat(o + i * 4, x) } }
        fun ptr(path: String, v: Long) = field(path).let { (o, _) -> if (f.ptrSize == 8) buf.putLong(o, v) else buf.putInt(o, v.toInt()) }
        fun str(path: String, v: String) = field(path).let { (o, fd) ->
            val b = v.toByteArray()
            for (i in 0 until fd.size) buf.put(o + i, if (i < b.size) b[i] else 0)
        }
    }

    fun objectAddress(name: String): Long {
        val f = file
        return f.blocks.first { it.code == "OB" && f.view("Object", it.offset)!!.sub("id")!!.str("name") == "OB$name" }.old
    }

    fun addModifiers(objectName: String, mods: List<Triple<String, Int, (Fill) -> Unit>>) {
        val f = file
        val ob = f.blocks.first { it.code == "OB" && f.view("Object", it.offset)!!.sub("id")!!.str("name") == "OB$objectName" }
        var next = f.blocks.maxOf { it.old + maxOf(it.len, 1) } + 0x1000
        val addrs = LongArray(mods.size) { next.also { next += 0x1000 } }
        val out = ByteArrayOutputStream()
        val large = bytes[7].toInt().toChar().isDigit()
        for ((i, m) in mods.withIndex()) {
            val (structName, type, fill) = m
            val st = f.struct(structName) ?: error("no struct $structName")
            val sdna = f.structByName[structName]!!
            val body = ByteBuffer.allocate(st.size).order(f.bb.order())
            val fl = Fill(f, st, body)
            fl.int("modifier.type", type)
            fl.int("modifier.mode", 3)
            fl.str("modifier.name", structName.removeSuffix("ModifierData"))
            if (i > 0) fl.ptr("modifier.prev", addrs[i - 1])
            if (i + 1 < mods.size) fl.ptr("modifier.next", addrs[i + 1])
            fill(fl)
            val hdr = ByteBuffer.allocate(if (large) 32 else 16 + f.ptrSize).order(f.bb.order())
            hdr.put("DATA".toByteArray())
            if (large) {
                hdr.putInt(4, sdna); hdr.putLong(8, addrs[i]); hdr.putLong(16, st.size.toLong()); hdr.putLong(24, 1L)
            } else {
                hdr.putInt(4, st.size)
                if (f.ptrSize == 8) hdr.putLong(8, addrs[i]) else hdr.putInt(8, addrs[i].toInt())
                hdr.putInt(8 + f.ptrSize, sdna); hdr.putInt(12 + f.ptrSize, 1)
            }
            out.write(hdr.array())
            out.write(body.array())
        }
        val insertAt = ob.offset + ob.len
        val patched = bytes.copyOfRange(0, insertAt) + out.toByteArray() + bytes.copyOfRange(insertAt, bytes.size)
        val pb = ByteBuffer.wrap(patched).order(f.bb.order())
        val obView = f.view("Object", ob.offset)!!
        val lb = obView.struct.fields["modifiers"]!!.offset
        val first = ob.offset + lb
        if (f.ptrSize == 8) {
            pb.putLong(first, addrs.first()); pb.putLong(first + 8, addrs.last())
        } else {
            pb.putInt(first, addrs.first().toInt()); pb.putInt(first + 4, addrs.last().toInt())
        }
        bytes = patched
    }

    fun addConstraint(objectName: String, bone: String?, type: Int, structName: String, fill: (Fill) -> Unit, con: (Fill) -> Unit = {}) {
        val f = file
        val ob = f.blocks.first { it.code == "OB" && f.view("Object", it.offset)!!.sub("id")!!.str("name") == "OB$objectName" }
        val obView = f.view("Object", ob.offset)!!
        val listAt = if (bone == null) ob.offset + obView.struct.fields["constraints"]!!.offset else {
            val ch = BlendAnimator(f).poseChannels(obView).first { it.str("name") == bone }
            ch.at + ch.struct.fields["constraints"]!!.offset
        }
        var next = f.blocks.maxOf { it.old + maxOf(it.len, 1) } + 0x1000
        val conAddr = next
        val dataAddr = next + 0x1000
        val large = bytes[7].toInt().toChar().isDigit()
        val out = ByteArrayOutputStream()
        fun block(structName: String, addr: Long, body: (Fill) -> Unit) {
            val st = f.struct(structName) ?: error("no struct $structName")
            val buf = ByteBuffer.allocate(st.size).order(f.bb.order())
            body(Fill(f, st, buf))
            val hdr = ByteBuffer.allocate(if (large) 32 else 16 + f.ptrSize).order(f.bb.order())
            hdr.put("DATA".toByteArray())
            if (large) {
                hdr.putInt(4, f.structByName[structName]!!); hdr.putLong(8, addr); hdr.putLong(16, st.size.toLong()); hdr.putLong(24, 1L)
            } else {
                hdr.putInt(4, st.size)
                if (f.ptrSize == 8) hdr.putLong(8, addr) else hdr.putInt(8, addr.toInt())
                hdr.putInt(8 + f.ptrSize, f.structByName[structName]!!); hdr.putInt(12 + f.ptrSize, 1)
            }
            out.write(hdr.array()); out.write(buf.array())
        }
        val oldFirst = if (f.ptrSize == 8) f.bb.getLong(listAt) else f.bb.getInt(listAt).toLong()
        block("bConstraint", conAddr) { c ->
            c.int("type", type); c.ptr("data", dataAddr); c.str("name", "C$type"); c.float("enforce", 1f)
            if (oldFirst != 0L) c.ptr("next", oldFirst)
            con(c)
        }
        block(structName, dataAddr, fill)
        val insertAt = ob.offset + ob.len
        val ins = out.toByteArray()
        val patched = bytes.copyOfRange(0, insertAt) + ins + bytes.copyOfRange(insertAt, bytes.size)
        val pb = ByteBuffer.wrap(patched).order(f.bb.order())
        val at = if (listAt >= insertAt) listAt + ins.size else listAt
        if (f.ptrSize == 8) {
            pb.putLong(at, conAddr); if (oldFirst == 0L) pb.putLong(at + 8, conAddr)
        } else {
            pb.putInt(at, conAddr.toInt()); if (oldFirst == 0L) pb.putInt(at + 4, conAddr.toInt())
        }
        bytes = patched
    }

    fun build(): ByteArray = bytes
}
