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
 *
 * Portions ported and adapted from Blender (https://www.blender.org/),
 * Copyright (C) 2001-2026 Blender Foundation and Blender Authors,
 * licensed under the GNU General Public License, version 2 or later (GPL-2.0-or-later).
 * Includes easing equations from source/blender/blenlib/intern/easing.cc,
 * Copyright (c) 2001 Robert Penner. All rights reserved. (BSD-3-Clause), and
 * Euler angle conversion algorithms by Ken Shoemake (Graphics Gems IV, 1994).
 * Ported to Kotlin and modified for FullFileStudio in 2026.
 */

package me.hlebkukleuski.fullfilestudio.feature.cadvector

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

internal class BlendAnimator(private val f: BlendFile) {

    class Kf(val x: Float, val y: Float, val lx: Float, val ly: Float, val rx: Float, val ry: Float, val ipo: Int, val easing: Int, val back: Float, val amplitude: Float, val period: Float)

    class Mod(val type: Int, val a: Int, val b: Int, val c: Int, val d: Int, val vals: FloatArray)

    class Curve(val path: String, val index: Int, val keys: List<Kf>, val extend: Int, val mods: List<Mod>, val round: Boolean)

    class Action(val at: Int, val name: String, val curves: Map<String, Curve>, val start: Float, val end: Float)

    class Strip(val action: Action, val start: Float, val end: Float, val actStart: Float, val actEnd: Float, val scale: Float, val repeat: Float, val blendIn: Float, val blendOut: Float, val influence: Float, val mode: Int, val extend: Int, val flag: Int)

    class Binding(val action: Action?, val actMode: Int, val actInfluence: Float, val tracks: List<List<Strip>>)

    class Target(val id: Int?, val path: String, val pchan: String, val chan: Int, val rotMode: Int, val flag: Int)

    class DVar(val name: String, val type: Int, val targets: List<Target>)

    class Driver(val type: Int, val expr: String, val vars: List<DVar>, val curve: Curve, val curval: Float)

    class IkTaskDef(val con: BlendConstraint, val owner: String, val tip: String)

    class SplineTree(val c: BlendConstraint, val chain: List<String>, val points: FloatArray, val totLength: Float) {
        var solved = false
    }

    class IkTree {
        val bones = LinkedHashSet<String>()
        val tasks = ArrayList<IkTaskDef>()
        var solved = false
    }

    class Clip(val name: String, val start: Float, val end: Float, val bindings: Map<Int, Binding>)

    class BoneRest(
        val name: String, val armMat: FloatArray, val parent: String?, val flag: Int, val length: Float, val inheritScale: Int,
        val head: FloatArray, val tail: FloatArray, val radHead: Float, val radTail: Float, val dist: Float, val weight: Float,
        val view: BlendFile.View? = null
    ) {
        val segments: Int get() = view?.int("segments", 1) ?: 1
    }

    val fps: Float
    val sceneStart: Float
    val sceneEnd: Float
    val currentFrame: Float
    val clips = ArrayList<Clip>()
    private val actions = HashMap<Long, Action>()
    private val drivers = HashMap<Int, Map<String, Driver>>()
    private val owners = HashMap<Int, BlendFile.View>()
    private val boneRest = HashMap<Int, Map<String, BoneRest>>()
    private val dynamicMemo = HashMap<Int, Boolean>()
    private val armAnimMemo = HashMap<Int, Boolean>()
    val staticFrame: Frame

    init {
        var fpsV = 24f
        var s = 1f
        var e = 250f
        var cur = 1f
        val r = f.activeScene()?.sub("r")
        if (r != null) {
            val fs = r.int("frs_sec", 24).toFloat()
            val base = r.float("frs_sec_base", 1f).takeIf { it > 0f } ?: 1f
            fpsV = (fs / base).takeIf { it > 0f && it.isFinite() } ?: 24f
            s = r.int("sfra", 1).toFloat()
            e = r.int("efra", 250).toFloat()
            cur = r.int("cfra", s.toInt()).toFloat()
        }
        fps = fpsV
        sceneStart = s
        sceneEnd = maxOf(s, e)
        currentFrame = cur
        for (b in f.blocks) {
            when (b.code) {
                "OB" -> f.view("Object", b.offset)?.let { owners[b.offset] = it }
                "KE" -> f.view("Key", b.offset)?.let { owners[b.offset] = it }
                "CU" -> f.view("Curve", b.offset)?.let { owners[b.offset] = it }
            }
        }
        staticFrame = Frame(null, cur)
        try {
            for ((at, o) in owners) animData(o)?.let { readDrivers(it) }?.takeIf { it.isNotEmpty() }?.let { drivers[at] = it }
            buildClips()
        } catch (_: Throwable) {
            clips.clear()
            drivers.clear()
        }
    }

    fun owner(at: Int): BlendFile.View? = owners[at]

    private fun cstr(ptr: Long): String {
        val at = f.offsetOf(ptr) ?: return ""
        var e = at
        while (e < f.bytes.size && f.bytes[e].toInt() != 0) e++
        return String(f.bytes, at, e - at, Charsets.UTF_8)
    }

    private fun idName(v: BlendFile.View): String = v.sub("id")?.str("name")?.drop(2) ?: ""

    fun listItems(lb: BlendFile.View?, type: String, limit: Int = 100_000): List<BlendFile.View> {
        lb ?: return emptyList()
        val out = ArrayList<BlendFile.View>()
        var p = lb.ptr("first")
        var guard = 0
        while (p != 0L && guard++ < limit) {
            val at = f.offsetOf(p) ?: break
            val v = f.view(type, at) ?: break
            out.add(v)
            p = v.ptr("next")
        }
        return out
    }

    private fun ptrArray(owner: BlendFile.View, field: String, countField: String): List<Pair<Int, String>> {
        val fd = owner.field(field) ?: return emptyList()
        val n = owner.int(countField).coerceIn(0, 100_000)
        val base = f.offsetOf(owner.ptr(field)) ?: return emptyList()
        return (0 until n).mapNotNull { i -> f.offsetOf(f.ptr(base + i * f.ptrSize))?.let { it to fd.type } }
    }

    private fun ptrViews(owner: BlendFile.View, field: String, countField: String): List<BlendFile.View> =
        ptrArray(owner, field, countField).mapNotNull { (at, type) -> f.view(type, at) }

    private fun parseCurve(fc: BlendFile.View, requirePath: Boolean = true): Curve? {
        val path = cstr(fc.ptr("rna_path"))
        if (requirePath && path.isEmpty()) return null
        val flag = fc.int("flag")
        if (flag and (1 shl 4) != 0) return null
        val n = fc.int("totvert").coerceIn(0, 1_000_000)
        val keys = ArrayList<Kf>(n)
        val bAt = f.offsetOf(fc.ptr("bezt"))
        val st = f.struct("BezTriple")
        if (bAt != null && st != null) {
            val vec = st.fields["vec"]?.offset ?: 0
            val ipoF = st.fields["ipo"]
            val easF = st.fields["easing"]
            val backF = st.fields["back"]
            val ampF = st.fields["amplitude"]
            val perF = st.fields["period"]
            for (i in 0 until n) {
                val at = bAt + i * st.size
                if (at + st.size > f.bytes.size) break
                fun v(k: Int) = f.bb.getFloat(at + vec + k * 4)
                keys.add(Kf(
                    v(3), v(4), v(0), v(1), v(6), v(7),
                    ipoF?.let { f.bytes[at + it.offset].toInt() } ?: 2,
                    easF?.let { f.bytes[at + it.offset].toInt() } ?: 0,
                    backF?.let { f.bb.getFloat(at + it.offset) } ?: 1.70158f,
                    ampF?.let { f.bb.getFloat(at + it.offset) } ?: 0.8f,
                    perF?.let { f.bb.getFloat(at + it.offset) } ?: 4.1f
                ))
            }
        } else {
            val pAt = f.offsetOf(fc.ptr("fpt"))
            val ps = f.struct("FPoint")
            if (pAt != null && ps != null) {
                val vec = ps.fields["vec"]?.offset ?: 0
                for (i in 0 until n) {
                    val at = pAt + i * ps.size
                    if (at + ps.size > f.bytes.size) break
                    val x = f.bb.getFloat(at + vec)
                    val y = f.bb.getFloat(at + vec + 4)
                    keys.add(Kf(x, y, x, y, x, y, 1, 0, 0f, 0f, 0f))
                }
            }
        }
        val mods = ArrayList<Mod>()
        for (m in listItems(fc.sub("modifiers"), "FModifier", 64)) {
            if (m.int("flag") and 2 != 0) continue
            val dAt = f.offsetOf(m.ptr("data")) ?: continue
            when (m.int("type")) {
                1 -> f.view("FMod_Generator", dAt)?.let { g ->
                    val cnt = g.int("arraysize").coerceIn(0, 64)
                    val cAt = f.offsetOf(g.ptr("coefficients"))
                    val co = FloatArray(cnt) { i -> if (cAt != null) f.bb.getFloat(cAt + i * 4) else 0f }
                    mods.add(Mod(1, g.int("mode"), g.int("flag"), g.int("poly_order"), 0, co))
                }
                4 -> f.view("FMod_Cycles", dAt)?.let { c ->
                    mods.add(Mod(4, c.int("before_mode"), c.int("after_mode"), c.int("before_cycles"), c.int("after_cycles"), FloatArray(0)))
                }
                8 -> f.view("FMod_Limits", dAt)?.let { l ->
                    val rect = l.sub("rect")
                    mods.add(Mod(8, l.int("flag"), 0, 0, 0, floatArrayOf(rect?.float("xmin") ?: 0f, rect?.float("ymin") ?: 0f, rect?.float("xmax") ?: 0f, rect?.float("ymax") ?: 0f)))
                }
                9 -> f.view("FMod_Stepped", dAt)?.let { s ->
                    mods.add(Mod(9, s.int("flag"), 0, 0, 0, floatArrayOf(s.float("step_size", 2f), s.float("offset"), s.float("start_frame"), s.float("end_frame"))))
                }
            }
        }
        if (keys.isEmpty() && mods.isEmpty() && requirePath) return null
        return Curve(path, fc.int("array_index"), keys, fc.int("extend"), mods, flag and ((1 shl 11) or (1 shl 12)) != 0)
    }

    private fun action(at: Int, slot: Int?): Action? {
        val key = (at.toLong() shl 32) or ((slot ?: -1).toLong() and 0xFFFFFFFFL)
        actions[key]?.let { return it }
        val act = f.view("bAction", at) ?: return null
        val curves = LinkedHashMap<String, Curve>()
        if (act.has("layer_array") && act.int("layer_array_num") > 0) {
            val datas = ptrViews(act, "strip_keyframe_data_array", "strip_keyframe_data_array_num")
            val useSlot = slot ?: ptrViews(act, "slot_array", "slot_array_num").firstOrNull()?.int("handle")
            for (layer in ptrViews(act, "layer_array", "layer_array_num")) {
                for (strip in ptrViews(layer, "strip_array", "strip_array_num")) {
                    val data = datas.getOrNull(strip.int("data_index")) ?: continue
                    for (bag in ptrViews(data, "channelbag_array", "channelbag_array_num")) {
                        if (useSlot != null && bag.int("slot_handle") != useSlot) continue
                        for (fc in ptrViews(bag, "fcurve_array", "fcurve_array_num")) parseCurve(fc)?.let { curves["${it.path}#${it.index}"] = it }
                    }
                }
            }
        } else {
            for (fc in listItems(act.sub("curves"), "FCurve")) parseCurve(fc)?.let { curves["${it.path}#${it.index}"] = it }
        }
        var s = Float.MAX_VALUE
        var e = -Float.MAX_VALUE
        for (c in curves.values) for (k in c.keys) {
            s = minOf(s, k.x)
            e = maxOf(e, k.x)
        }
        if (act.int("flag") and (1 shl 12) != 0 && act.has("frame_start")) {
            s = act.float("frame_start")
            e = act.float("frame_end")
        }
        if (s > e) {
            s = 0f
            e = 0f
        }
        val a = Action(at, idName(act), curves, s, e)
        actions[key] = a
        return a
    }

    private fun animData(o: BlendFile.View): BlendFile.View? {
        val at = f.offsetOf(o.ptr("adt")) ?: return null
        return f.view("AnimData", at)
    }

    private fun binding(adt: BlendFile.View, override: Action? = null): Binding {
        val flag = adt.int("flag")
        val slot = if (adt.has("slot_handle")) adt.int("slot_handle") else null
        val tweak = flag and 4 != 0
        val actPtr = if (tweak && adt.ptr("tmpact") != 0L) adt.ptr("tmpact") else adt.ptr("action")
        val tweakSlot = if (tweak && adt.has("tmp_slot_handle") && adt.ptr("tmpact") != 0L) adt.int("tmp_slot_handle") else slot
        val act = override ?: f.offsetOf(actPtr)?.let { action(it, tweakSlot) }
        val solo = flag and 1 != 0
        val tracks = if (override != null || flag and 2 != 0) emptyList() else listItems(adt.sub("nla_tracks"), "NlaTrack").mapNotNull { tr ->
            val tf = tr.int("flag")
            if (tf and (1 shl 2) != 0 || tf and (1 shl 10) != 0 || (solo && tf and (1 shl 3) == 0)) return@mapNotNull null
            val strips = listItems(tr.sub("strips"), "NlaStrip").mapNotNull { st ->
                val sf = st.int("flag")
                if (sf and (1 shl 12) != 0 || st.int("type") != 0) return@mapNotNull null
                val aAt = f.offsetOf(st.ptr("act")) ?: return@mapNotNull null
                val stripSlot = if (st.has("action_slot_handle")) st.int("action_slot_handle") else slot
                val a = action(aAt, stripSlot) ?: return@mapNotNull null
                Strip(
                    a, st.float("start"), st.float("end"), st.float("actstart"), st.float("actend"),
                    st.float("scale", 1f).let { abs(it) }.takeIf { it > 0f } ?: 1f, st.float("repeat", 1f).takeIf { it > 0f } ?: 1f,
                    st.float("blendin"), st.float("blendout"), st.float("influence", 1f), st.int("blendmode"), st.int("extendmode"), sf
                )
            }.sortedBy { it.start }
            strips.ifEmpty { null }
        }
        val infl = adt.float("act_influence", 1f).takeIf { it > 0f } ?: 1f
        return Binding(act, adt.int("act_blendmode"), infl, tracks)
    }

    private fun readDrivers(adt: BlendFile.View): Map<String, Driver> {
        val res = HashMap<String, Driver>()
        for (fc in listItems(adt.sub("drivers"), "FCurve", 10_000)) {
            val curve = parseCurve(fc, false) ?: continue
            if (curve.path.isEmpty()) continue
            val dAt = f.offsetOf(fc.ptr("driver")) ?: continue
            val drv = f.view("ChannelDriver", dAt) ?: continue
            if (drv.int("flag") and 1 != 0) continue
            val tst = f.struct("DriverTarget") ?: continue
            val vars = listItems(drv.sub("variables"), "DriverVar", 64).map { dv ->
                val base = dv.at + (dv.field("targets")?.offset ?: 0)
                val n = dv.int("num_targets").coerceIn(0, 8)
                val targets = (0 until n).map { i ->
                    val t = f.View(tst, base + i * tst.size)
                    Target(f.offsetOf(t.ptr("id")), cstr(t.ptr("rna_path")), t.str("pchan_name"), t.int("transChan"), t.int("rotation_mode"), t.int("flag"))
                }
                DVar(dv.str("name"), dv.int("type"), targets)
            }
            res["${curve.path}#${curve.index}"] = Driver(drv.int("type"), drv.str("expression"), vars, curve, fc.float("curval"))
        }
        return res
    }

    private fun buildClips() {
        val base = HashMap<Int, Binding>()
        for ((at, o) in owners) {
            val adt = animData(o) ?: continue
            val b = binding(adt)
            if (b.action != null || b.tracks.isNotEmpty()) base[at] = b
        }
        val armatures = owners.values.filter { it.struct.type == "Object" && it.int("type") == 25 }
        if (base.isNotEmpty() || driversDynamic()) clips.add(Clip("Scene", sceneStart, sceneEnd, base))
        val seen = HashSet<Int>()
        for (blk in f.blocks) {
            if (blk.code != "AC" || !seen.add(blk.offset)) continue
            val a = action(blk.offset, null) ?: continue
            if (a.curves.isEmpty()) continue
            val boneNames = a.curves.values.mapNotNull { boneName(it.path) }.toHashSet()
            if (boneNames.isEmpty()) continue
            val target = armatures.firstOrNull { arm -> poseChannels(arm).any { it.str("name") in boneNames } } ?: continue
            val current = base[target.at]
            if (current != null && current.action?.at == a.at && current.tracks.isEmpty()) continue
            val map = HashMap(base)
            map[target.at] = Binding(a, 0, 1f, emptyList())
            clips.add(Clip(a.name.ifBlank { "Action" }, a.start, maxOf(a.start, a.end), map))
        }
    }

    private fun driversDynamic(): Boolean = drivers.values.any { m -> m.values.any { d -> d.vars.any { v -> v.targets.any { it.id != null } } || d.expr.contains("frame") } }

    fun boneName(path: String): String? = quoted(path, "pose.bones[")

    fun poseChannels(ob: BlendFile.View): List<BlendFile.View> {
        val poseAt = f.offsetOf(ob.ptr("pose")) ?: return emptyList()
        val pose = f.view("bPose", poseAt) ?: return emptyList()
        return listItems(pose.sub("chanbase"), "bPoseChannel")
    }

    fun armatureData(ob: BlendFile.View): BlendFile.View? = f.offsetOf(ob.ptr("data"))?.let { f.view("bArmature", it) }

    fun bones(ob: BlendFile.View): Map<String, BoneRest> = boneRest.getOrPut(ob.at) {
        val res = HashMap<String, BoneRest>()
        val arm = armatureData(ob) ?: return@getOrPut res
        fun walk(list: BlendFile.View?, parent: String?, depth: Int) {
            if (depth > 256) return
            for (b in listItems(list, "Bone")) {
                val name = b.str("name")
                val m = b.floats("arm_mat", 16) ?: CleanRoomGltf.IDENTITY
                val bf = b.int("flag")
                val inherit = if (b.has("inherit_scale_mode")) b.int("inherit_scale_mode") else if (bf and (1 shl 15) != 0) 4 else 0
                res[name] = BoneRest(
                    name, m, parent, bf, b.float("length"), inherit,
                    b.floats("arm_head", 3) ?: floatArrayOf(m[12], m[13], m[14]), b.floats("arm_tail", 3) ?: floatArrayOf(m[12], m[13], m[14]),
                    b.float("rad_head"), b.float("rad_tail"), b.float("dist"), b.float("weight", 1f), b
                )
                walk(b.sub("childbase"), name, depth + 1)
            }
        }
        walk(arm.sub("bonebase"), null, 0)
        res
    }

    fun deformBones(ob: BlendFile.View): Set<String> = bones(ob).values.filter { it.flag and (1 shl 12) == 0 }.map { it.name }.toHashSet()

    fun boneLength(ob: BlendFile.View, name: String): Float = bones(ob)[name]?.length ?: 0f

    fun boneRestMatrix(ob: BlendFile.View, name: String): FloatArray? = bones(ob)[name]?.armMat

    private val transformPaths = setOf(
        "location", "rotation_euler", "rotation_quaternion", "rotation_axis_angle", "scale",
        "delta_location", "delta_rotation_euler", "delta_rotation_quaternion", "delta_scale"
    )

    private fun ownerAnimatesAny(at: Int, test: (String) -> Boolean): Boolean {
        if (drivers[at]?.values?.any { test(it.curve.path) } == true) return true
        return clips.any { c ->
            val b = c.bindings[at] ?: return@any false
            (b.action?.curves?.values?.any { test(it.path) } == true) || b.tracks.any { t -> t.any { s -> s.action.curves.values.any { test(it.path) } } }
        }
    }

    var shrinkTargets: ((Frame, BlendFile.View) -> ShrinkTree?)? = null

    fun modifierAnimated(ob: BlendFile.View, modName: String): Boolean = clips.isNotEmpty() && ownerAnimatesAny(ob.at) { it.startsWith("modifiers[\"$modName\"]") }

    private val objCons = HashMap<Int, List<BlendConstraint>>()
    private val boneCons = HashMap<Int, Map<String, List<BlendConstraint>>>()

    fun objectConstraints(ob: BlendFile.View): List<BlendConstraint> = objCons.getOrPut(ob.at) {
        try {
            readConstraints(f, ob.sub("constraints")) { lb, t -> listItems(lb, t) }.filter { it.active }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun boneConstraints(arm: BlendFile.View): Map<String, List<BlendConstraint>> = boneCons.getOrPut(arm.at) {
        val m = HashMap<String, List<BlendConstraint>>()
        for (ch in poseChannels(arm)) {
            val l = try {
                readConstraints(f, ch.sub("constraints")) { lb, t -> listItems(lb, t) }.filter { it.active }
            } catch (_: Throwable) {
                emptyList()
            }
            if (l.isNotEmpty()) m[ch.str("name")] = l
        }
        m
    }

    private fun constraintTargets(c: BlendConstraint): List<Int> {
        val out = ArrayList<Int>()
        for ((t, _) in c.targets) if (t != null) out.add(t)
        c.spaceObject?.let { out.add(it) }
        if (c.type == 3) f.offsetOf(c.data.ptr("poletar"))?.let { out.add(it) }
        return out
    }

    private fun targetMoves(at: Int, self: Int, depth: Int): Boolean {
        if (at == self) return false
        val o = owners[at] ?: return false
        if (o.struct.type != "Object") return false
        return dynamic(o, depth + 1) || (o.int("type") == 25 && armatureAnimated(o, depth + 1)) || (o.int("type") == 2 && curveTimeAnimated(o))
    }

    fun curveTimeAnimated(ob: BlendFile.View): Boolean {
        val cuAt = f.offsetOf(ob.ptr("data")) ?: return false
        return clips.isNotEmpty() && ownerAnimatesAny(cuAt) { it == "eval_time" }
    }

    private val paths = HashMap<Int, BlendPath?>()

    fun curvePath(ob: BlendFile.View): BlendPath? {
        val cuAt = f.offsetOf(ob.ptr("data")) ?: return null
        return paths.getOrPut(cuAt) {
            try {
                f.view("Curve", cuAt)?.let { BlendCurves.path(f, it) { lb, t -> listItems(lb, t) } }
            } catch (_: Throwable) {
                null
            }
        }
    }

    fun curveData(ob: BlendFile.View): BlendFile.View? = f.offsetOf(ob.ptr("data"))?.let { f.view("Curve", it) }

    fun armatureAnimated(ob: BlendFile.View): Boolean = armatureAnimated(ob, 0)

    private fun armatureAnimated(ob: BlendFile.View, depth: Int): Boolean {
        armAnimMemo[ob.at]?.let { return it }
        if (clips.isEmpty() || depth > 64) return false
        val arm = armatureData(ob)
        if (arm != null && arm.int("flag") and 1 != 0) {
            armAnimMemo[ob.at] = false
            return false
        }
        armAnimMemo[ob.at] = false
        var d = ownerAnimatesAny(ob.at) { it.startsWith("pose.bones[") }
        if (!d) d = boneConstraints(ob).values.any { l -> l.any { c -> constraintTargets(c).any { targetMoves(it, ob.at, depth) } || (c.type == 12 && c.data.int("flag") and 2 != 0) } }
        if (!d && boneConstraints(ob).values.any { l -> l.any { c -> c.type == 30 || (c.tarSpace == 0 || c.ownSpace == 0) && c.targets.any { it.first == ob.at } } }) d = dynamic(ob, depth + 1)
        armAnimMemo[ob.at] = d
        return d
    }

    fun dynamic(ob: BlendFile.View): Boolean = dynamic(ob, 0)

    private fun dynamic(ob: BlendFile.View, depth: Int): Boolean {
        dynamicMemo[ob.at]?.let { return it }
        if (clips.isEmpty() || depth > 64) return false
        dynamicMemo[ob.at] = false
        var d = ownerAnimatesAny(ob.at) { it in transformPaths }
        if (!d) {
            val parent = f.offsetOf(ob.ptr("parent"))?.let { owners[it] }
            if (parent != null) {
                d = dynamic(parent, depth + 1) || ((ob.int("partype") and 15) == 7 && parent.int("type") == 25 && armatureAnimated(parent, depth + 1)) ||
                    ((ob.int("partype") and 15) == 0 && parent.int("type") == 2 && curveTimeAnimated(parent))
            }
        }
        if (!d) d = objectConstraints(ob).any { c -> constraintTargets(c).any { targetMoves(it, ob.at, depth) } || (c.type == 12 && c.data.int("flag") and 2 != 0) }
        dynamicMemo[ob.at] = d
        return d
    }

    private fun keyPaths(keyAt: Int, blockName: String): List<String> {
        val idx = owners[keyAt]?.let { k -> listItems(k.sub("block"), "KeyBlock").indexOfFirst { it.str("name") == blockName } } ?: -1
        return listOf("key_blocks[\"$blockName\"].value", "keys[\"$blockName\"].value", "key_blocks[$idx].value", "keys[$idx].value")
    }

    fun keyAnimated(keyAt: Int, blockName: String): Boolean {
        if (clips.isEmpty()) return false
        val paths = keyPaths(keyAt, blockName)
        return ownerAnimatesAny(keyAt) { it in paths || it == "eval_time" }
    }

    fun keyPath(keyAt: Int, blockName: String): String {
        val paths = keyPaths(keyAt, blockName)
        return paths.firstOrNull { p -> ownerAnimatesAny(keyAt) { it == p } } ?: paths[0]
    }

    private fun storedValue(owner: Int, path: String, index: Int): Float {
        val o = owners[owner] ?: return 0f
        if (o.struct.type == "Curve") return if (path == "eval_time") o.float("ctime") else 0f
        if (o.struct.type == "Key") {
            if (path == "eval_time") return o.float("ctime")
            val blocks = listItems(o.sub("block"), "KeyBlock")
            val inner = path.substringAfter('[', "").substringBefore(']')
            val kb = (if (inner.startsWith("\"")) blocks.firstOrNull { it.str("name") == inner.trim('"') } else inner.toIntOrNull()?.let { blocks.getOrNull(it) }) ?: return 0f
            return if (path.endsWith(".value")) kb.float("curval") else 0f
        }
        val bone = quoted(path, "pose.bones[")
        if (bone != null) {
            val ch = poseChannels(o).firstOrNull { it.str("name") == bone } ?: return defaultFor(path, index)
            return when (path.substringAfterLast('.')) {
                "location" -> ch.floats("loc", 3)?.getOrNull(index) ?: 0f
                "rotation_euler" -> ch.floats("eul", 3)?.getOrNull(index) ?: 0f
                "rotation_quaternion" -> ch.floats("quat", 4)?.getOrNull(index) ?: if (index == 0) 1f else 0f
                "rotation_axis_angle" -> if (index == 0) ch.float("rotAngle") else ch.floats("rotAxis", 3)?.getOrNull(index - 1) ?: 0f
                "scale" -> ch.floats("size", 3)?.getOrNull(index) ?: 1f
                "bbone_easein" -> ch.float("ease1")
                "bbone_easeout" -> ch.float("ease2")
                "bbone_rollin" -> ch.float("roll1")
                "bbone_rollout" -> ch.float("roll2")
                "bbone_curveinx" -> if (ch.has("curve_in_x")) ch.float("curve_in_x") else ch.float("curveInX")
                "bbone_curveinz", "bbone_curveiny" -> if (ch.has("curve_in_z")) ch.float("curve_in_z") else ch.float("curveInY")
                "bbone_curveoutx" -> if (ch.has("curve_out_x")) ch.float("curve_out_x") else ch.float("curveOutX")
                "bbone_curveoutz", "bbone_curveouty" -> if (ch.has("curve_out_z")) ch.float("curve_out_z") else ch.float("curveOutY")
                "bbone_scalein" -> pchanScale(ch, "scale_in", "scaleIn", "scale_in_y")[index.coerceIn(0, 2)]
                "bbone_scaleout" -> pchanScale(ch, "scale_out", "scaleOut", "scale_out_y")[index.coerceIn(0, 2)]
                else -> defaultFor(path, index)
            }
        }
        fun arr(vararg names: String, n: Int): FloatArray? = names.firstNotNullOfOrNull { o.floats(it, n) }
        return when (path) {
            "location" -> arr("loc", n = 3)?.getOrNull(index) ?: 0f
            "delta_location" -> arr("dloc", n = 3)?.getOrNull(index) ?: 0f
            "rotation_euler" -> arr("rot", n = 3)?.getOrNull(index) ?: 0f
            "delta_rotation_euler" -> arr("drot", n = 3)?.getOrNull(index) ?: 0f
            "rotation_quaternion" -> arr("quat", n = 4)?.getOrNull(index) ?: if (index == 0) 1f else 0f
            "delta_rotation_quaternion" -> arr("dquat", n = 4)?.getOrNull(index) ?: if (index == 0) 1f else 0f
            "rotation_axis_angle" -> if (index == 0) o.float("rotAngle") else arr("rotAxis", n = 3)?.getOrNull(index - 1) ?: 0f
            "scale" -> arr("scale", "size", n = 3)?.getOrNull(index) ?: 1f
            "delta_scale" -> arr("dscale", n = 3)?.getOrNull(index) ?: arr("dsize", n = 3)?.getOrNull(index)?.plus(1f) ?: 1f
            else -> defaultFor(path, index)
        }
    }

    private fun pchanScale(v: BlendFile.View, vec: String, legacyX: String, legacyZ: String): FloatArray {
        val fd = v.field(vec)
        if (fd != null && fd.size >= 12) return v.floats(vec, 3) ?: floatArrayOf(1f, 1f, 1f)
        val x = if (v.has(legacyX)) v.float(legacyX, 1f) else 1f
        val z = if (v.has(legacyZ)) v.float(legacyZ, 1f) else x
        return floatArrayOf(x, 1f, z)
    }

    private fun defaultFor(path: String, index: Int): Float = when {
        path.endsWith("bbone_scalein") || path.endsWith("bbone_scaleout") -> 1f
        path.endsWith("scale") -> 1f
        path.endsWith("rotation_quaternion") && index == 0 -> 1f
        else -> 0f
    }

    private fun quoted(path: String, prefix: String): String? {
        if (!path.startsWith(prefix)) return null
        val q1 = path.indexOf('"')
        val q2 = path.indexOf('"', q1 + 1)
        return if (q1 >= 0 && q2 > q1) path.substring(q1 + 1, q2) else null
    }

    private class Hit(val strip: Strip, val frame: Float, val influence: Float)

    private fun mapStrip(s: Strip, frame: Float): Float {
        val actLen = (s.actEnd - s.actStart).let { if (it == 0f) 1f else it }
        val period = actLen * s.scale
        val local = frame - s.start
        val wrapped = if (period != 0f) local - floor(local / period) * period else 0f
        if (s.flag and (1 shl 11) != 0) {
            if (frame >= s.end) return s.actStart
            return s.actEnd - wrapped / s.scale
        }
        if (frame >= s.end && s.repeat == floor(s.repeat)) return s.actEnd
        return s.actStart + wrapped / s.scale
    }

    private fun stripInfluence(s: Strip, frame: Float): Float {
        if (s.flag and (1 shl 5) != 0) return s.influence.coerceIn(0f, 1f)
        if (s.blendIn > 0f && frame < s.start + s.blendIn) return ((frame - s.start) / s.blendIn).coerceIn(0f, 1f)
        if (s.blendOut > 0f && frame > s.end - s.blendOut) return ((s.end - frame) / s.blendOut).coerceIn(0f, 1f)
        return 1f
    }

    private fun trackHit(track: List<Strip>, frame: Float): Hit? {
        for (s in track) if (frame >= s.start && frame <= s.end) return Hit(s, mapStrip(s, frame), stripInfluence(s, frame))
        val first = track.first()
        if (frame < first.start) return if (first.extend == 0) Hit(first, mapStrip(first, first.start), stripInfluence(first, first.start)) else null
        val prev = track.lastOrNull { it.end < frame } ?: return null
        if (prev.extend == 2) return null
        return Hit(prev, mapStrip(prev, prev.end), stripInfluence(prev, prev.end))
    }

    private fun blend(lower: Float, value: Float, mode: Int, influence: Float, path: String): Float = when (mode) {
        1 -> lower + value * influence
        2 -> lower - value * influence
        3 -> lower * (influence * value + (1f - influence))
        4 -> when {
            path.endsWith("scale") -> lower * (if (value > 0f) value.pow(influence) else 1f + (value - 1f) * influence)
            path.endsWith("rotation_quaternion") -> lower + (value - lower) * influence
            else -> lower + value * influence
        }
        else -> lower + (value - lower) * influence
    }

    fun value(b: Binding?, path: String, index: Int, frame: Float, fallback: Float): Float {
        b ?: return fallback
        val key = "$path#$index"
        var v = fallback
        var found = false
        for (track in b.tracks) {
            val hit = trackHit(track, frame) ?: continue
            val c = hit.strip.action.curves[key] ?: continue
            v = blend(v, evaluate(c, hit.frame), hit.strip.mode, hit.influence, path)
            found = true
        }
        val c = b.action?.curves?.get(key)
        if (c != null) {
            v = blend(v, evaluate(c, frame), b.actMode, b.actInfluence, path)
            found = true
        }
        return if (found) v else fallback
    }

    inner class Frame(val clip: Clip?, val frame: Float, val forceEval: Boolean = false) {
        private val memo = HashMap<String, Float>()
        private val worlds = HashMap<Int, FloatArray>()
        private val poses = HashMap<Int, HashMap<String, FloatArray>>()
        private var depth = 0

        fun prop(owner: Int, path: String, index: Int): Float {
            val key = "$owner|$path|$index"
            memo[key]?.let { return it }
            if (path.startsWith("data.shape_keys.")) {
                val o = owners[owner]
                val keyAt = o?.let { f.offsetOf(it.ptr("data")) }?.let { f.view("Mesh", it) }?.let { f.offsetOf(it.ptr("key")) }
                val v = if (keyAt != null) prop(keyAt, path.removePrefix("data.shape_keys."), index) else 0f
                memo[key] = v
                return v
            }
            var v = storedValue(owner, path, index)
            if (clip != null) {
                v = value(clip.bindings[owner], path, index, frame, v)
                val d = drivers[owner]?.get("$path#$index")
                if (d != null && depth < 24) {
                    depth++
                    try {
                        v = evalDriver(d, this)
                    } finally {
                        depth--
                    }
                }
            }
            memo[key] = v
            return v
        }

        fun modifierValue(ob: BlendFile.View, modName: String, rna: String, index: Int, stored: Float): Float {
            if (clip == null) return stored
            val path = "modifiers[\"$modName\"].$rna"
            val key = "${ob.at}|$path|$index"
            memo[key]?.let { return it }
            var v = value(clip.bindings[ob.at], path, index, frame, stored)
            val d = drivers[ob.at]?.get("$path#$index")
            if (d != null && depth < 24) {
                depth++
                try {
                    v = evalDriver(d, this)
                } finally {
                    depth--
                }
            }
            memo[key] = v
            return v
        }

        private fun vec(owner: Int, path: String, n: Int): FloatArray = FloatArray(n) { prop(owner, path, it) }

        private fun rotation(owner: Int, prefix: String, mode: Int, delta: Boolean): FloatArray {
            val p = if (delta) "${prefix}delta_" else prefix
            return when {
                mode > 0 -> eulerMatrix(vec(owner, "${p}rotation_euler", 3), mode)
                mode == -1 && !delta -> {
                    val a = vec(owner, "${p}rotation_axis_angle", 4)
                    axisAngle(a[1], a[2], a[3], a[0])
                }
                mode == -1 -> CleanRoomGltf.IDENTITY
                else -> quatMatrix(vec(owner, "${p}rotation_quaternion", 4))
            }
        }

        fun objectLocal(ob: BlendFile.View): FloatArray {
            val mode = ob.int("rotmode", 1)
            val loc = vec(ob.at, "location", 3)
            val dloc = vec(ob.at, "delta_location", 3)
            val s = vec(ob.at, "scale", 3)
            val ds = vec(ob.at, "delta_scale", 3)
            val r = CleanRoomGltf.mul(rotation(ob.at, "", mode, true), rotation(ob.at, "", mode, false))
            val m = CleanRoomGltf.mul(r, CleanRoomGltf.trs(null, null, floatArrayOf(s[0] * ds[0], s[1] * ds[1], s[2] * ds[2])))
            m[12] = loc[0] + dloc[0]
            m[13] = loc[1] + dloc[1]
            m[14] = loc[2] + dloc[2]
            return m
        }

        fun channelLocal(arm: BlendFile.View, name: String): FloatArray {
            val ch = poseChannels(arm).firstOrNull { it.str("name") == name } ?: return CleanRoomGltf.IDENTITY.copyOf()
            val prefix = "pose.bones[\"$name\"]."
            val mode = ch.int("rotmode", 0)
            val loc = vec(arm.at, "${prefix}location", 3)
            val s = vec(arm.at, "${prefix}scale", 3)
            val r = rotation(arm.at, prefix, mode, false)
            val m = CleanRoomGltf.mul(r, CleanRoomGltf.trs(null, null, s))
            m[12] = loc[0]
            m[13] = loc[1]
            m[14] = loc[2]
            return m
        }

        fun storedWorld(ob: BlendFile.View): FloatArray = staticFrame.world(ob)

        fun world(ob: BlendFile.View): FloatArray = world(ob, 0)

        private val evaluating = HashSet<Int>()

        private fun world(ob: BlendFile.View, d: Int): FloatArray {
            worlds[ob.at]?.let { return it }
            val stored = if (!forceEval && (clip == null || !dynamic(ob))) mat4(ob, "object_to_world") ?: mat4(ob, "obmat") else null
            if (stored != null) {
                worlds[ob.at] = stored
                return stored
            }
            if (d > 64 || ob.at in evaluating) return objectLocal(ob)
            evaluating.add(ob.at)
            try {
                val l = objectLocal(ob)
                val parent = f.offsetOf(ob.ptr("parent"))?.let { owners[it] }
                var w = if (parent == null) l else {
                    val pinv = mat4(ob, "parentinv") ?: CleanRoomGltf.IDENTITY
                    CleanRoomGltf.mul(parentMatrix(ob, parent, d), CleanRoomGltf.mul(pinv, l))
                }
                val cons = objectConstraints(ob)
                if (cons.isNotEmpty()) {
                    worlds[ob.at] = w
                    w = solve(cons, w, ob, null)
                }
                worlds[ob.at] = w
                return w
            } finally {
                evaluating.remove(ob.at)
            }
        }

        private fun parentMatrix(ob: BlendFile.View, parent: BlendFile.View, d: Int): FloatArray {
            val type = ob.int("partype") and 15
            val pw = world(parent, d + 1)
            if (type == 7 && parent.int("type") == 25) {
                val bone = ob.str("parsubstr")
                val pm = bonePose(parent, bone) ?: return pw
                val tail = CleanRoomGltf.IDENTITY.copyOf().also { it[13] = boneLength(parent, bone) }
                return CleanRoomGltf.mul(pw, CleanRoomGltf.mul(pm, tail))
            }
            if (type == 0 && parent.int("type") == 2) {
                val cu = curveData(parent)
                if (cu != null && cu.int("flag") and 8 != 0) {
                    val path = curvePath(parent)
                    if (path != null) {
                        val pathlen = cu.int("pathlen")
                        var ct = prop(cu.at, "eval_time", 0)
                        if (pathlen != 0) ct /= pathlen
                        if (cu.int("flag") and 32 != 0) ct = ct.coerceIn(0f, 1f)
                        val smp = path.where(ct)
                        if (smp != null) {
                            var m = CleanRoomGltf.IDENTITY.copyOf()
                            if (cu.int("flag") and 16 != 0) m = RigMath.quatMat(BlendPath.normalizeQ(BlendPath.applyTrack(smp.quat, ob.int("trackflag", 1), ob.int("upflag", 2))))
                            if (cu.int("flag") and (1 shl 12) != 0) m = CleanRoomGltf.mul(CleanRoomGltf.trs(null, null, floatArrayOf(smp.radius, smp.radius, smp.radius)), m)
                            m[12] = smp.vec[0]; m[13] = smp.vec[1]; m[14] = smp.vec[2]
                            return CleanRoomGltf.mul(pw, m)
                        }
                    }
                }
            }
            if (type == 5 || type == 6) {
                if (this === staticFrame) return pw
                val sw = staticFrame.world(ob)
                val pinv = mat4(ob, "parentinv") ?: CleanRoomGltf.IDENTITY
                val ps = CleanRoomGltf.mul(sw, invert(CleanRoomGltf.mul(pinv, staticFrame.objectLocal(ob))))
                val rel = CleanRoomGltf.mul(pw, invert(staticFrame.world(parent)))
                val v = transformPoint(rel, ps[12], ps[13], ps[14])
                return CleanRoomGltf.IDENTITY.copyOf().also { it[12] = v[0]; it[13] = v[1]; it[14] = v[2] }
            }
            return pw
        }

        private inner class ArmState(val arm: BlendFile.View) {
            val rest = bones(arm)
            val res = HashMap<String, FloatArray>()
            val busy = HashSet<String>()
            val cons = boneConstraints(arm)
            val chans = poseChannels(arm).associateBy { it.str("name") }
            val trees = HashMap<String, IkTree>()
            val splines = HashMap<String, SplineTree>()
            val restPose = armatureData(arm)?.let { it.int("flag") and 1 != 0 } ?: false
            val usesStored: Boolean

            init {
                var stored = !restPose && !forceEval && !(clip != null && armatureAnimated(arm))
                if (stored) {
                    for (ch in chans.values) {
                        val m = ch.floats("pose_mat", 16)
                        if (m == null || m.all { it == 0f }) {
                            stored = false
                            res.clear()
                            break
                        }
                        res[ch.str("name")] = m
                    }
                    if (res.isEmpty()) stored = false
                }
                usesStored = stored
                if (!stored && !restPose) {
                    buildTrees()
                    buildSplines()
                }
            }

            private fun buildSplines() {
                for ((tip, l) in cons) {
                    val c = l.firstOrNull { c ->
                        c.type == 22 && c.active && c.targets.firstOrNull()?.first?.let { owners[it] }?.int("type") == 2
                    } ?: continue
                    val d = c.data
                    val chainLen = d.int("chainlen")
                    val chain = ArrayList<String>()
                    var cur: String? = tip
                    while (cur != null && chain.size < chainLen) {
                        chain.add(cur)
                        cur = rest[cur]?.parent
                    }
                    if (chain.isEmpty()) continue
                    val lens = chain.map { rest[it]?.length ?: 0f }
                    val tot = lens.sum()
                    val seg = chain.size
                    val stored = f.offsetOf(d.ptr("points"))?.let { at -> d.int("numpoints").takeIf { it >= seg + 1 }?.let { n -> FloatArray(n) { f.bb.getFloat(at + it * 4) } } }
                    val pts = if (d.int("flag") and 1 != 0 && stored != null) stored else FloatArray(chainLen + 1).also { p ->
                        p[0] = 1f
                        for (i in 0 until seg) p[i + 1] = if (d.int("flag") and 8 != 0 || tot == 0f) p[i] - 1f / seg else p[i] - lens[i] / tot
                    }
                    if (pts[seg] < 0f) pts[seg] = 0f
                    val tree = SplineTree(c, chain, pts, tot)
                    for (b in chain) if (b !in splines) splines[b] = tree
                }
            }

            private fun solveSpline(t: SplineTree) {
                t.solved = true
                for (i in t.chain.indices.reversed()) {
                    val n = t.chain[i]
                    if (res[n] != null) continue
                    busy.add(n)
                    try {
                        res[n] = whereIs(n)
                    } finally {
                        busy.remove(n)
                    }
                }
                val tar = t.c.targets.first().first?.let { owners[it] } ?: return
                val path = curvePath(tar) ?: return
                if (path.points.size < 2 || path.accum.isEmpty()) return
                val d = t.c.data
                val flag = d.int("flag")
                val ys = d.int("yScaleMode")
                val xzs = d.int("xzScaleMode")
                val enforce = t.c.influence
                val toArm = if (flag and 2 == 0) CleanRoomGltf.mul(invert(world(arm)), world(tar)) else invert(world(arm))
                val splineLen = path.accum[path.accum.size - 1]
                var curvePos = 0f
                val curveScale = if (ys != 1 && t.totLength != 0f) t.totLength / splineLen else 1f
                var locrot = CleanRoomGltf.IDENTITY.copyOf()
                var prevTail = FloatArray(3)
                var prevRad = 1f
                var prevSeg = 0
                for (index in t.chain.indices.reversed()) {
                    val name = t.chain[index]
                    val b = rest[name] ?: continue
                    val old = res[name] ?: continue
                    if (b.length < 1.1920929E-7f) {
                        val smp = path.where(curvePos) ?: continue
                        val pp = RigMath.point(toArm, smp.vec)
                        res[name] = old.copyOf().also { it[12] = pp[0]; it[13] = pp[1]; it[14] = pp[2] }
                        continue
                    }
                    val oldHead = floatArrayOf(old[12], old[13], old[14])
                    val oldTail = transformPoint(old, 0f, b.length, 0f)
                    var poseHead = RigMath.point(locrot, oldHead)
                    var poseTail = RigMath.point(locrot, oldTail)
                    val origHead = poseHead.copyOf()
                    val curveLen = t.points[index] - t.points[index + 1]
                    val boneLen = RigMath.len(RigMath.sub(poseTail, poseHead))
                    val pointStart = curvePos
                    val poseScale = boneLen / b.length
                    val baseScale = if (ys == 2) poseScale else 1f
                    var pointEnd = pointStart + curveLen * baseScale * curveScale
                    curvePos = pointEnd
                    var radius = 1f
                    var blend = 0f
                    if (pointStart < 1f) {
                        val vec: FloatArray
                        var rad: Float
                        if (pointStart == 0f) {
                            val smp = path.where(pointStart)
                            vec = smp?.vec ?: FloatArray(3)
                            rad = smp?.radius ?: 1f
                        } else {
                            vec = prevTail.copyOf()
                            rad = prevRad
                        }
                        radius = rad
                        poseHead = RigMath.point(toArm, vec)
                        if (ys != 1) {
                            val sphere = if (ys == 2) boneLen else b.length
                            val r = SplineMath.tailOnSpline(path, vec, sphere, prevSeg, pointEnd)
                            prevSeg = r.seg
                            pointEnd = r.pos
                            rad = r.radius
                            prevRad = rad
                            prevTail = r.tail.copyOf()
                            poseTail = RigMath.point(toArm, r.tail)
                            curvePos = pointEnd
                        } else {
                            val smp = path.where(pointEnd)
                            if (smp != null) {
                                prevRad = smp.radius
                                prevTail = smp.vec.copyOf()
                                poseTail = RigMath.point(toArm, smp.vec)
                            }
                        }
                        blend = if (pointEnd >= 1f) (1f - pointStart) / (pointEnd - pointStart) else 1f
                    }
                    var splineVec = RigMath.sub(poseTail, poseHead)
                    var scaleFac = RigMath.len(splineVec) / b.length
                    val base = CleanRoomGltf.mul(locrot, old).also { it[12] = 0f; it[13] = 0f; it[14] = 0f }
                    val rmat = normalizedAxes(base).also { it[12] = 0f; it[13] = 0f; it[14] = 0f }
                    splineVec = RigMath.norm(splineVec)
                    val ry = RigMath.col(rmat, 1)
                    var raxis = RigMath.cross(ry, splineVec)
                    val rn = RigMath.len(raxis)
                    if (rn < 1.1920929E-7f) {
                        val order = SplineMath.axisSort(splineVec)
                        val tmp = FloatArray(3)
                        tmp[order[1]] = 1f
                        raxis = RigMath.cross(tmp, splineVec)
                    } else raxis = RigMath.scale(raxis, 1f / rn)
                    var rangle = acos(RigMath.dot(ry, splineVec).coerceIn(-1f, 1f))
                    rangle *= enforce * blend
                    val dmat = axisAngle(raxis[0], raxis[1], raxis[2], rangle)
                    val pose = normalizedAxes(CleanRoomGltf.mul(dmat, rmat)).also { it[12] = 0f; it[13] = 0f; it[14] = 0f }
                    val baseRot = CleanRoomGltf.mul(dmat, base).also { it[12] = 0f; it[13] = 0f; it[14] = 0f }
                    locrot = RigMath.mulM3M4(dmat, locrot)
                    fun scaleCol(c: Int, k: Float) { for (r in 0 until 3) pose[c * 4 + r] *= k }
                    scaleCol(1, scaleFac)
                    if (xzs != 0) {
                        if (xzs == 1 || flag and 128 != 0) {
                            scaleCol(0, RigMath.len(RigMath.col(old, 0)))
                            scaleCol(2, RigMath.len(RigMath.col(old, 2)))
                            scaleFac /= poseScale
                        }
                        when (xzs) {
                            2 -> {
                                val k = if (abs(scaleFac) != 0f) (1f / abs(scaleFac)).coerceIn(0.0001f, 100000f) else 1f
                                scaleCol(0, k); scaleCol(2, k)
                            }
                            3 -> {
                                val k = if (abs(scaleFac) != 0f) {
                                    var bulge = (1f / abs(scaleFac)).pow(d.float("bulge"))
                                    val smooth = d.float("bulge_smooth")
                                    if (bulge > 1f && flag and 64 != 0) {
                                        val bmax = maxOf(d.float("bulge_max"), 1f)
                                        val hard = minOf(bulge, bmax)
                                        val range = bmax - 1f
                                        val sc = if (range > 0f) 1f / range else 0f
                                        val soft = 1f + range * kotlin.math.atan((bulge - 1f) * sc) / (PI.toFloat() / 2f)
                                        bulge = hard + (soft - hard) * smooth
                                    }
                                    if (bulge < 1f && flag and 32 != 0) {
                                        val bmin = d.float("bulge_min").coerceIn(0f, 1f)
                                        val hard = maxOf(bulge, bmin)
                                        val range = 1f - bmin
                                        val sc = if (range > 0f) 1f / range else 0f
                                        val soft = 1f - range * kotlin.math.atan((1f - bulge) * sc) / (PI.toFloat() / 2f)
                                        bulge = hard + (soft - hard) * smooth
                                    }
                                    sqrt(bulge)
                                } else 1f
                                scaleCol(0, k); scaleCol(2, k)
                            }
                        }
                    }
                    if (flag and 16 == 0) { scaleCol(0, radius); scaleCol(2, radius) }
                    val f2 = enforce * blend
                    for (c in 0 until 3) for (r in 0 until 3) {
                        val i = c * 4 + r
                        pose[i] = baseRot[i] + (pose[i] - baseRot[i]) * f2
                    }
                    if (flag and 2 != 0) poseHead = origHead
                    else if (enforce < 1f) poseHead = if (index < t.chain.size - 1) origHead else RigMath.lerp(origHead, poseHead, enforce)
                    pose[12] = poseHead[0]; pose[13] = poseHead[1]; pose[14] = poseHead[2]
                    res[name] = pose
                    val origTail = RigMath.dir(locrot, oldTail)
                    val newTail = transformPoint(pose, 0f, b.length, 0f)
                    locrot[12] = newTail[0] - origTail[0]; locrot[13] = newTail[1] - origTail[1]; locrot[14] = newTail[2] - origTail[2]
                }
            }

            private fun buildTrees() {
                val list = ArrayList<IkTree>()
                for ((owner, l) in cons) for (c in l) {
                    if (c.type != 3 || c.data.int("type") != 0) continue
                    val tar = c.targets.firstOrNull()?.first?.let { owners[it] } ?: continue
                    if (tar.int("type") == 25 && c.targets[0].second.isEmpty()) continue
                    val tip = if (c.data.int("flag") and 1 != 0) owner else rest[owner]?.parent ?: continue
                    val limit = c.data.int("rootbone")
                    val chain = ArrayList<String>()
                    var cur: String? = tip
                    while (cur != null) {
                        chain.add(cur)
                        if (chain.size == limit || chain.size > 255) break
                        cur = rest[cur]?.parent
                    }
                    if (chain.isEmpty()) continue
                    val merged = list.filter { t -> t.bones.any { it in chain } }
                    val tree = IkTree()
                    for (m in merged) {
                        tree.bones.addAll(m.bones)
                        tree.tasks.addAll(m.tasks)
                        list.remove(m)
                    }
                    tree.bones.addAll(chain)
                    tree.tasks.add(IkTaskDef(c, owner, tip))
                    list.add(tree)
                }
                for (t in list) for (b in t.bones) trees[b] = t
            }

            fun get(name: String): FloatArray? {
                res[name]?.let { return it }
                val b = rest[name] ?: return null
                if (restPose) {
                    res[name] = b.armMat
                    return b.armMat
                }
                if (name in busy) return CleanRoomGltf.mul(b.armMat, channelLocal(arm, name))
                val tree = trees[name]
                if (tree != null && !tree.solved) {
                    solveTree(tree)
                    res[name]?.let { return it }
                }
                val spline = splines[name]
                if (spline != null && !spline.solved) {
                    solveSpline(spline)
                    res[name]?.let { return it }
                }
                busy.add(name)
                try {
                    val m = whereIs(name)
                    res[name] = m
                    return m
                } finally {
                    busy.remove(name)
                }
            }

            fun parentTransform(name: String): Pair<FloatArray, FloatArray> {
                val b = rest[name] ?: return CleanRoomGltf.IDENTITY to CleanRoomGltf.IDENTITY
                val parent = b.parent?.let { rest[it] }
                val noLocal = b.flag and (1 shl 22) != 0
                if (parent == null) {
                    val loc = if (noLocal) CleanRoomGltf.IDENTITY.copyOf().also { it[12] = b.armMat[12]; it[13] = b.armMat[13]; it[14] = b.armMat[14] } else b.armMat
                    return b.armMat to loc
                }
                val pp = get(parent.name) ?: parent.armMat
                val offs = CleanRoomGltf.mul(invert(parent.armMat), b.armMat)
                val hinge = b.flag and (1 shl 9) != 0
                val full = CleanRoomGltf.mul(pp, offs)
                val rs = when {
                    hinge && b.inheritScale == 4 -> {
                        val s = RigMath.size(pp)
                        CleanRoomGltf.mul(CleanRoomGltf.mul(CleanRoomGltf.trs(null, null, s), parent.armMat), offs)
                    }
                    hinge -> CleanRoomGltf.mul(parent.armMat, offs)
                    b.inheritScale == 0 -> full
                    b.inheritScale == 1 -> {
                        val t = pp.copyOf()
                        val y = RigMath.col(t, 1)
                        val yl = RigMath.len(y)
                        val (_, r, s) = RigMath.locRotSize(t)
                        val avg = cbrtAbs(s[0] * s[1] * s[2])
                        CleanRoomGltf.mul(RigMath.compose(RigMath.loc(pp), r, floatArrayOf(avg, if (yl > 0f) yl else avg, avg)), offs)
                    }
                    b.inheritScale == 2 -> {
                        val (l, r, s) = RigMath.locRotSize(pp)
                        val avg = cbrtAbs(s[0] * s[1] * s[2])
                        CleanRoomGltf.mul(RigMath.compose(l, r, floatArrayOf(avg, avg, avg)), offs)
                    }
                    else -> CleanRoomGltf.mul(normalizedAxes(pp), offs)
                }
                val loc = when {
                    noLocal -> {
                        val bl = transformPoint(pp, offs[12], offs[13], offs[14])
                        val r = pp.copyOf()
                        r[12] = 0f; r[13] = 0f; r[14] = 0f
                        val m = CleanRoomGltf.IDENTITY.copyOf().also { it[12] = bl[0]; it[13] = bl[1]; it[14] = bl[2] }
                        CleanRoomGltf.mul(m, r)
                    }
                    hinge || b.inheritScale != 0 -> full
                    else -> rs
                }
                return rs to loc
            }

            fun applyParent(name: String, chan: FloatArray): FloatArray {
                val (rs, lm) = parentTransform(name)
                val m = CleanRoomGltf.mul(rs, chan)
                val t = transformPoint(lm, chan[12], chan[13], chan[14])
                m[12] = t[0]; m[13] = t[1]; m[14] = t[2]
                return m
            }

            fun poseToBone(name: String, m: FloatArray): FloatArray {
                val (rs, lm) = parentTransform(name)
                val r = CleanRoomGltf.mul(invert(rs), m)
                val t = transformPoint(invert(lm), m[12], m[13], m[14])
                r[12] = t[0]; r[13] = t[1]; r[14] = t[2]
                return r
            }

            private fun whereIs(name: String): FloatArray {
                var pose = applyParent(name, channelLocal(arm, name))
                val l = cons[name]?.filter { it.type != 3 && it.type != 22 }
                if (!l.isNullOrEmpty()) {
                    val armW = world(arm)
                    res[name] = pose
                    val w = solve(l, CleanRoomGltf.mul(armW, pose), arm, name)
                    pose = CleanRoomGltf.mul(invert(armW), w)
                }
                return pose
            }

            private fun solveTree(tree: IkTree) {
                tree.solved = true
                val order = tree.bones.sortedBy { depthOf(it) }
                for (n in order) {
                    if (res[n] != null) continue
                    busy.add(n)
                    try {
                        res[n] = whereIs(n)
                    } finally {
                        busy.remove(n)
                    }
                }
                val index = HashMap<String, Int>()
                val segs = ArrayList<IkSegment>()
                val deltas = ArrayList<FloatArray>()
                for (n in order) {
                    val b = rest[n] ?: continue
                    val pose = res[n] ?: continue
                    val pName = b.parent
                    val parentInTree = pName != null && index.containsKey(pName)
                    val parentPose = pName?.let { get(it) }
                    deltas.add(if (parentPose != null) CleanRoomGltf.mul(invert(parentPose), pose) else pose)
                    val ch = chans[n]
                    val ikflag = ch?.int("ikflag") ?: 0
                    val stiff = ch?.floats("stiffness", 3) ?: FloatArray(3)
                    val lmin = ch?.floats("limitmin", 3) ?: FloatArray(3)
                    val lmax = ch?.floats("limitmax", 3) ?: FloatArray(3)
                    val dof = BooleanArray(3) { ikflag and (1 shl it) == 0 }
                    val limited = (0 until 3).any { ikflag and (8 shl it) != 0 }
                    val limits = if (limited) FloatArray(6) { k ->
                        val axis = k / 2
                        if (ikflag and (8 shl axis) == 0) Float.NaN else if (k % 2 == 0) lmin[axis] else lmax[axis]
                    } else null
                    val pRest = pName?.let { rest[it] }
                    val restLocal = if (pRest != null) RigMath.mul(RigMath.inv(RigMath.rot3(CleanRoomGltf.mul(invert(pRest.armMat), b.armMat))), RigMath.rot3(CleanRoomGltf.mul(invert(parentPose ?: pRest.armMat), pose))) else
                        RigMath.mul(RigMath.inv(RigMath.rot3(b.armMat)), RigMath.rot3(pose))
                    val rootFrame = if (!parentInTree && parentPose != null && pRest != null) parentPose.copyOf().also {
                        it[12] += parentPose[4] * pRest.length; it[13] += parentPose[5] * pRest.length; it[14] += parentPose[6] * pRest.length
                    } else null
                    val stretch = if (tree.tasks.any { it.con.data.int("flag") and 16 != 0 }) (ch?.float("ikstretch") ?: 0f).let { it * it } else 0f
                    index[n] = segs.size
                    segs.add(IkSegment(
                        n, if (parentInTree) index[pName]!! else -1, pose, rootFrame, b.length, dof,
                        FloatArray(3) { (1f - stiff[it]).coerceIn(0.001f, 1f) }, limits, restLocal, stretch
                    ))
                }
                if (segs.isEmpty()) return
                val armInv = invert(world(arm))
                val tasks = ArrayList<IkTask>()
                var iterations = 0
                var pole: Triple<Int, FloatArray, Float>? = null
                for (t in tree.tasks) {
                    val si = index[t.tip] ?: continue
                    val c = t.con
                    val d = c.data
                    val flag = d.int("flag")
                    val tw = targetWorld(c.targets[0].first, c.targets[0].second, c.headTail) ?: continue
                    val tm = CleanRoomGltf.mul(armInv, tw)
                    val fkPose = res[t.tip] ?: continue
                    val len = rest[t.tip]?.length ?: 0f
                    val fkTail = floatArrayOf(fkPose[12] + fkPose[4] * len, fkPose[13] + fkPose[5] * len, fkPose[14] + fkPose[6] * len)
                    var goal: FloatArray? = if (flag and 32 != 0 || !d.has("flag")) RigMath.loc(tm) else null
                    var goalRot: FloatArray? = if (flag and 2 != 0) RigMath.rot3(tm) else null
                    if (c.influence < 1f) {
                        goal = goal?.let { RigMath.lerp(fkTail, it, c.influence) }
                        goalRot = goalRot?.let { RigMath.quatMat(RigMath.slerp(RigMath.quat(fkPose), RigMath.quat(it), c.influence)) }
                    }
                    if (goal == null && goalRot == null) continue
                    tasks.add(IkTask(si, goal, goalRot, d.float("weight", 1f).takeIf { it > 0f } ?: 1f, d.float("orientweight", 1f)))
                    iterations = maxOf(iterations, d.int("iterations", 500))
                    val poleAt = f.offsetOf(d.ptr("poletar"))
                    if (poleAt != null && goal != null) {
                        val pw = targetWorld(poleAt, d.str("polesubtarget"), 0f)
                        if (pw != null) pole = Triple(si, RigMath.loc(CleanRoomGltf.mul(armInv, pw)), d.float("poleangle"))
                    }
                }
                if (tasks.isEmpty()) return
                val solver = IkSolver(segs, tasks)
                val p = pole
                if (p != null && tasks.size == 1) {
                    var root = p.first
                    while (segs[root].parent >= 0) root = segs[root].parent
                    solver.poleAlign(root, p.first, tasks[0].goal!!, p.second, p.third)
                }
                solver.solve(iterations.coerceIn(1, 5000))
                for ((i, s) in segs.withIndex()) {
                    val pName = rest[s.name]?.parent
                    val parent = pName?.let { res[it] }
                    val bc = solver.basisChange(i)
                    res[s.name] = if (parent != null) RigMath.mul(parent, deltas[i], bc) else RigMath.mul(deltas[i], bc)
                }
            }

            private fun depthOf(n: String): Int {
                var d = 0
                var c = rest[n]?.parent
                while (c != null && d < 512) {
                    d++
                    c = rest[c]?.parent
                }
                return d
            }
        }

        private val armStates = HashMap<Int, ArmState>()

        private fun armState(arm: BlendFile.View): ArmState = armStates.getOrPut(arm.at) { ArmState(arm) }

        fun bonePose(arm: BlendFile.View, name: String): FloatArray? = armState(arm).get(name)

        private val bboneCache = HashMap<String, Pair<List<FloatArray>, List<FloatArray>>?>()

        private fun bfield(v: BlendFile.View, vararg names: String, def: Float = 0f): Float {
            for (n in names) if (v.has(n)) return v.float(n, def)
            return def
        }

        private fun scaleVec(v: BlendFile.View, vec: String, legacyX: String, legacyZ: String): FloatArray {
            val fd = v.field(vec)
            if (fd != null && fd.size >= 12) return v.floats(vec, 3) ?: floatArrayOf(1f, 1f, 1f)
            val x = bfield(v, legacyX, def = 1f)
            val z = if (v.has(legacyZ)) bfield(v, legacyZ, def = 1f) else x
            return floatArrayOf(x, 1f, z)
        }

        fun bboneDeform(arm: BlendFile.View, name: String): Pair<List<FloatArray>, List<FloatArray>>? {
            val key = "${arm.at}|$name"
            if (bboneCache.containsKey(key)) return bboneCache[key]
            val r = try {
                computeBBone(arm, name)
            } catch (_: Throwable) {
                null
            }
            bboneCache[key] = r
            return r
        }

        fun bboneRestSegments(arm: BlendFile.View, name: String): List<FloatArray>? {
            val st = armState(arm)
            val b = st.rest[name] ?: return null
            val bv = b.view ?: return null
            if (b.segments <= 1) return null
            return BBoneMath.compute(params(arm, st, b, bv, null, true)).toList()
        }

        private fun computeBBone(arm: BlendFile.View, name: String): Pair<List<FloatArray>, List<FloatArray>>? {
            val st = armState(arm)
            val b = st.rest[name] ?: return null
            val bv = b.view ?: return null
            if (b.segments <= 1) return null
            val pose = st.get(name) ?: return null
            val rest = params(arm, st, b, bv, null, true)
            val posed = params(arm, st, b, bv, pose, false)
            val br = BBoneMath.compute(rest)
            val bp = BBoneMath.compute(posed)
            val armInv = invert(b.armMat)
            val deform = ArrayList<FloatArray>()
            val bases = ArrayList<FloatArray>()
            for (a in br.indices) {
                deform.add(RigMath.mul(pose, bp[a], invert(br[a]), armInv))
                bases.add(normalizedAxes(CleanRoomGltf.mul(b.armMat, br[a])))
            }
            return deform to bases
        }

        private fun params(arm: BlendFile.View, st: ArmState, b: BoneRest, bv: BlendFile.View, pose: FloatArray?, rest: Boolean): BBoneParams {
            val p = BBoneParams()
            p.segments = b.segments
            p.length = b.length
            if (!rest && pose != null) {
                val sc = RigMath.size(pose)
                if (abs(sc[0] - sc[2]) > 1e-6f) {
                    p.doScale = true
                    p.scale = sc
                }
            }
            fun boneByPtr(field: String): BoneRest? {
                val at = f.offsetOf(bv.ptr(field)) ?: return null
                val name = f.view("Bone", at)?.str("name") ?: return null
                return st.rest[name]
            }
            val prevType = bv.int("bbone_prev_type")
            val nextType = bv.int("bbone_next_type")
            val connected = b.flag and 16 != 0
            val prev = if (prevType == 0) (if (connected) b.parent?.let { st.rest[it] } else null) else boneByPtr("bbone_prev")
            val next = if (nextType == 0) st.rest.values.firstOrNull { it.parent == b.name && it.flag and 16 != 0 } else boneByPtr("bbone_next")
            val imat = when {
                rest -> invert(b.armMat)
                p.doScale -> invert(normalizedAxes(pose!!))
                else -> invert(pose!!)
            }
            fun head(x: BoneRest, xp: FloatArray?) = if (rest || xp == null) x.head else RigMath.loc(xp)
            fun tail(x: BoneRest, xp: FloatArray?) = if (rest || xp == null) x.tail else floatArrayOf(xp[12] + xp[4] * x.length, xp[13] + xp[5] * x.length, xp[14] + xp[6] * x.length)
            val selfHead = head(b, pose)
            val selfTail = tail(b, pose)
            var prevScale = floatArrayOf(1f, 1f, 1f)
            var nextScale = floatArrayOf(1f, 1f, 1f)
            val prevFlag = bv.int("bbone_prev_flag")
            val nextFlag = bv.int("bbone_next_flag")
            if (prev != null) {
                val pp = if (rest) null else st.get(prev.name)
                p.usePrev = true
                var done = false
                val h1: FloatArray = when (prevType) {
                    2 -> if (rest) { p.prevH = floatArrayOf(0f, 0f, 0f); done = true; floatArrayOf(0f, 0f, 0f) } else RigMath.sub(selfHead, RigMath.sub(head(prev, pp), prev.head))
                    3 -> RigMath.sub(selfHead, RigMath.sub(tail(prev, pp), head(prev, pp)))
                    else -> {
                        p.prevBBone = prev.segments > 1
                        head(prev, pp)
                    }
                }
                if (!done) p.prevH = transformPoint(imat, h1[0], h1[1], h1[2])
                if (!p.prevBBone) p.prevMat = CleanRoomGltf.mul(imat, if (rest) prev.armMat else pp ?: prev.armMat)
                if (!rest && prevFlag and 15 != 0 && pp != null) prevScale = RigMath.size(st.poseToBone(prev.name, pp))
            }
            if (next != null) {
                val np = if (rest) null else st.get(next.name)
                p.useNext = true
                var done = false
                val h2: FloatArray = when (nextType) {
                    2 -> if (rest) { p.nextH = floatArrayOf(0f, b.length, 0f); done = true; floatArrayOf(0f, 0f, 0f) } else RigMath.add(selfTail, RigMath.sub(head(next, np), next.head))
                    3 -> RigMath.add(selfTail, RigMath.sub(tail(next, np), head(next, np)))
                    else -> {
                        p.nextBBone = next.segments > 1
                        tail(next, np)
                    }
                }
                if (!done) p.nextH = transformPoint(imat, h2[0], h2[1], h2[2])
                p.nextMat = CleanRoomGltf.mul(imat, if (rest) next.armMat else np ?: next.armMat)
                if (!rest && nextFlag and 15 != 0 && np != null) nextScale = RigMath.size(st.poseToBone(next.name, np))
            }
            val ch = st.chans[b.name]
            val prefix = "pose.bones[\"${b.name}\"]."
            fun pv(path: String, idx: Int, def: Float) = if (rest || ch == null) def else prop(arm.at, prefix + path, idx)
            p.ease1 = bfield(bv, "ease1") + pv("bbone_easein", 0, 0f)
            p.ease2 = bfield(bv, "ease2") + pv("bbone_easeout", 0, 0f)
            p.roll1 = bfield(bv, "roll1") + pv("bbone_rollin", 0, 0f)
            p.roll2 = bfield(bv, "roll2") + pv("bbone_rollout", 0, 0f)
            if (bv.int("bbone_flag") and 1 != 0 && prev != null) {
                p.roll1 += prev.view?.let { bfield(it, "roll2") } ?: 0f
                if (!rest) p.roll1 += prop(arm.at, "pose.bones[\"${prev.name}\"].bbone_rollout", 0)
            }
            p.scaleIn = scaleVec(bv, "scale_in", "scaleIn", "scale_in_y")
            p.scaleOut = scaleVec(bv, "scale_out", "scaleOut", "scale_out_y")
            if (!rest) for (k in 0 until 3) {
                p.scaleIn[k] *= pv("bbone_scalein", k, 1f)
                p.scaleOut[k] *= pv("bbone_scaleout", k, 1f)
            }
            p.curveInX = bfield(bv, "curve_in_x", "curveInX") + pv("bbone_curveinx", 0, 0f)
            p.curveInZ = bfield(bv, "curve_in_z", "curveInY") + pv("bbone_curveinz", 0, 0f)
            p.curveOutX = bfield(bv, "curve_out_x", "curveOutX") + pv("bbone_curveoutx", 0, 0f)
            p.curveOutZ = bfield(bv, "curve_out_z", "curveOutY") + pv("bbone_curveoutz", 0, 0f)
            if (bv.int("bbone_flag") and 2 != 0) {
                p.ease1 *= p.scaleIn[1]; p.curveInX *= p.scaleIn[1]; p.curveInZ *= p.scaleIn[1]
                p.ease2 *= p.scaleOut[1]; p.curveOutX *= p.scaleOut[1]; p.curveOutZ *= p.scaleOut[1]
            }
            if (prevFlag and 1 != 0) p.scaleIn[0] *= prevScale[0]
            if (prevFlag and 2 != 0) p.scaleIn[1] *= prevScale[1]
            if (prevFlag and 4 != 0) p.scaleIn[2] *= prevScale[2]
            if (prevFlag and 8 != 0) { p.ease1 *= prevScale[1]; p.curveInX *= prevScale[1]; p.curveInZ *= prevScale[1] }
            if (nextFlag and 1 != 0) p.scaleOut[0] *= nextScale[0]
            if (nextFlag and 2 != 0) p.scaleOut[1] *= nextScale[1]
            if (nextFlag and 4 != 0) p.scaleOut[2] *= nextScale[2]
            if (nextFlag and 8 != 0) { p.ease2 *= nextScale[1]; p.curveOutX *= nextScale[1]; p.curveOutZ *= nextScale[1] }
            return p
        }

        fun pose(arm: BlendFile.View): Map<String, FloatArray> {
            poses[arm.at]?.let { return it }
            val st = armState(arm)
            for (n in st.rest.keys) st.get(n)
            val m = HashMap(st.res)
            poses[arm.at] = m
            return m
        }

        fun targetWorld(at: Int?, sub: String, headTail: Float): FloatArray? {
            val ob = at?.let { owners[it] } ?: return null
            if (ob.struct.type != "Object") return null
            val w = world(ob)
            if (sub.isEmpty() || ob.int("type") != 25) return w
            val pm = bonePose(ob, sub) ?: return w
            if (headTail < 0.000001f) return CleanRoomGltf.mul(w, pm)
            val len = boneLength(ob, sub)
            val m = pm.copyOf()
            m[12] += pm[4] * len * headTail
            m[13] += pm[5] * len * headTail
            m[14] += pm[6] * len * headTail
            return CleanRoomGltf.mul(w, m)
        }

        private fun rotOrder(ob: BlendFile.View, bone: String?): Int {
            val mode = if (bone != null) poseChannels(ob).firstOrNull { it.str("name") == bone }?.int("rotmode", 0) ?: 0 else ob.int("rotmode", 1)
            return if (mode in 1..6) mode else 1
        }

        private fun convert(m: FloatArray, from: Int, to: Int, ob: BlendFile.View, bone: String?, cobBone: String?, custom: FloatArray?): FloatArray {
            if (from == to) return m
            if (bone != null && ob.int("type") == 25) {
                val st = armState(ob)
                val rest = st.rest[bone]
                fun ownDiff(): FloatArray {
                    var diff = rest?.armMat ?: CleanRoomGltf.IDENTITY
                    val cr = cobBone?.let { st.rest[it] }
                    if (cr != null) diff = CleanRoomGltf.mul(invert(cr.armMat), diff)
                    return diff.copyOf().also { it[12] = 0f; it[13] = 0f; it[14] = 0f }
                }
                return when (from) {
                    0 -> if (to == 5) CleanRoomGltf.mul(invert(custom ?: CleanRoomGltf.IDENTITY), m) else {
                        val p = CleanRoomGltf.mul(invert(world(ob)), m)
                        if (to == 1 || to == 3 || to == 6) convert(p, 2, to, ob, bone, cobBone, custom) else p
                    }
                    2 -> when (to) {
                        1 -> if (rest != null) st.poseToBone(bone, m) else m
                        6 -> convert(if (rest != null) st.poseToBone(bone, m) else m, 1, 6, ob, bone, cobBone, custom)
                        3 -> if (rest != null) CleanRoomGltf.mul(invert(rest.armMat), m) else m
                        else -> {
                            val w = CleanRoomGltf.mul(world(ob), m)
                            if (to == 5) convert(w, 0, 5, ob, bone, cobBone, custom) else w
                        }
                    }
                    1 -> if (to == 6) {
                        val diff = ownDiff()
                        CleanRoomGltf.mul(CleanRoomGltf.mul(diff, m), invert(diff))
                    } else {
                        val p = if (rest != null) st.applyParent(bone, m) else m
                        if (to == 0 || to == 3 || to == 5) convert(p, 2, to, ob, bone, cobBone, custom) else p
                    }
                    6 -> {
                        val diff = ownDiff()
                        val l = CleanRoomGltf.mul(CleanRoomGltf.mul(invert(diff), m), diff)
                        if (to == 1) l else convert(l, 1, to, ob, bone, cobBone, custom)
                    }
                    3 -> {
                        val p = if (rest != null) CleanRoomGltf.mul(rest.armMat, m) else m
                        if (to == 2) p else convert(p, 2, to, ob, bone, cobBone, custom)
                    }
                    5 -> convert(CleanRoomGltf.mul(custom ?: CleanRoomGltf.IDENTITY, m), 0, to, ob, bone, cobBone, custom)
                    else -> m
                }
            }
            if (from == 0 && to == 5) return CleanRoomGltf.mul(invert(custom ?: CleanRoomGltf.IDENTITY), m)
            if (from == 5) return convert(CleanRoomGltf.mul(custom ?: CleanRoomGltf.IDENTITY, m), 0, to, ob, bone, cobBone, custom)
            if (to == 5) return convert(convert(m, from, 0, ob, bone, cobBone, custom), 0, 5, ob, bone, cobBone, custom)
            val parent = f.offsetOf(ob.ptr("parent"))?.let { owners[it] }
            val diff = if (parent != null) CleanRoomGltf.mul(world(parent), mat4(ob, "parentinv") ?: CleanRoomGltf.IDENTITY) else
                normalizedAxes(objectLocal(ob)).also { it[12] = 0f; it[13] = 0f; it[14] = 0f }
            return when {
                from == 0 && to == 1 -> CleanRoomGltf.mul(invert(diff), m)
                from == 1 && to == 0 -> CleanRoomGltf.mul(diff, m)
                else -> m
            }
        }

        private fun solve(cons: List<BlendConstraint>, start: FloatArray, ob: BlendFile.View, bone: String?): FloatArray {
            var m = start
            val order = rotOrder(ob, bone)
            for (c in cons) {
                if (!c.active || c.type == 3 || c.type == 22) continue
                val custom = if (c.ownSpace == 5 || c.tarSpace == 5) targetWorld(c.spaceObject, c.spaceSubtarget, 0f) else null
                val old = m
                val own = convert(m, 0, c.ownSpace, ob, bone, null, custom)
                val tars = c.targets.map { (t, sub) ->
                    val tw = targetWorld(t, sub, c.headTail) ?: return@map null
                    val tob = owners[t!!] ?: return@map null
                    convert(tw, 0, c.tarSpace, tob, sub.takeIf { it.isNotEmpty() && tob.int("type") == 25 }, bone, custom)
                }
                val res = try {
                    evaluateConstraint(c, own, tars, ob, bone, order)
                } catch (_: Throwable) {
                    null
                } ?: continue
                m = convert(res, c.ownSpace, 0, ob, bone, null, custom)
                if (c.influence < 1f) m = RigMath.interp(old, m, c.influence)
            }
            return m
        }

        private fun evaluateConstraint(c: BlendConstraint, own: FloatArray, tars: List<FloatArray?>, ob: BlendFile.View, bone: String?, order: Int): FloatArray? {
            val d = c.data
            val t0 = tars.firstOrNull()
            return when (c.type) {
                1 -> t0?.let {
                    val tob = c.targets[0].first?.let { a -> owners[a] }
                    val tOrder = if (tob != null) rotOrder(tob, c.targets[0].second.takeIf { s -> s.isNotEmpty() && tob.int("type") == 25 }) else 1
                    ConstraintMath.childOf(own, it, d, order, tOrder)
                }
                2 -> t0?.let { ConstraintMath.trackTo(own, it, d.int("reserved1"), d.int("reserved2"), d.int("flags") and 1 != 0) }
                5 -> ConstraintMath.limitRot(own, d, order)
                6 -> ConstraintMath.limitLoc(own, d)
                7 -> ConstraintMath.limitSize(own, d)
                8 -> t0?.let { ConstraintMath.copyRotation(own, it, d, order) }
                9 -> t0?.let { ConstraintMath.copyLocation(own, it, d) }
                10 -> t0?.let { ConstraintMath.copyScale(own, it, d) }
                12 -> actionConstraint(c, own, t0, ob, bone)
                13 -> t0?.let { ConstraintMath.lockTrack(own, it, d.int("trackflag"), d.int("lockflag")) }
                14 -> t0?.let { ConstraintMath.distLimit(own, it, d) }
                15 -> t0?.let { ConstraintMath.stretchTo(own, it, d) }
                16 -> t0?.let { ConstraintMath.floor(own, it, d) }
                19 -> t0?.let { ConstraintMath.transform(own, it, d, order) }
                21 -> t0?.let { ConstraintMath.dampTrack(own, it, d.int("trackflag")) }
                23 -> t0?.let { ConstraintMath.copyTransforms(own, it, d.int("mix_mode")) }
                24 -> ConstraintMath.sameVolume(own, d)
                25 -> ConstraintMath.pivot(own, t0, d)
                30 -> armatureConstraint(c, own, ob, bone)
                20 -> shrinkwrapConstraint(c, own, ob, bone)
                4 -> followPath(c, own)
                18 -> clampTo(c, own)
                else -> null
            }
        }

        private fun shrinkwrapConstraint(c: BlendConstraint, own: FloatArray, ob: BlendFile.View, bone: String?): FloatArray? {
            val d = c.data
            val tob = c.targets.firstOrNull()?.first?.let { owners[it] } ?: return null
            if (tob.int("type") != 1) return null
            val tree = shrinkTargets?.invoke(this, tob) ?: return null
            val type = d.int("shrinkType")
            val mode = d.int("shrinkMode")
            val flag = d.int("flag")
            val dist = d.float("dist")
            val track = flag and 4 != 0
            val l2t = CleanRoomGltf.mul(invert(world(tob)), own)
            val t2l = invert(l2t)
            var co = floatArrayOf(0f, 0f, 0f)
            var trackNo: FloatArray? = null
            var fail = false
            when (type) {
                0, 2, 3 -> {
                    val q = RigMath.point(l2t, co)
                    val nr = ShrinkTree.Nearest()
                    when (type) {
                        2 -> tree.nearestVertex(q, nr)
                        3 -> if (tree.hasFaces) tree.targetProject(q, nr)
                        else -> if (tree.hasFaces) tree.nearestSurface(q, nr)
                    }
                    if (nr.index < 0) fail = true else {
                        var r = q
                        if (type != 2) {
                            if (track) trackNo = ShrinkMath.normalized(RigMath.dir(t2l, ShrinkMath.normalized(tree.smoothNormal(nr.index, nr.co, nr.no))))
                            r = ShrinkMath.snap(tree, null, null, mode, nr.index, nr.co, nr.no, dist, q)
                        } else {
                            val dd = sqrt(ShrinkMath.distSq(q, nr.co))
                            if (dd != 0f) r = RigMath.lerp(q, nr.co, (dd - dist) / dd)
                        }
                        co = RigMath.point(t2l, r)
                    }
                }
                1 -> {
                    var no = FloatArray(3)
                    val axis = d.int("projAxis")
                    if (axis in 0..2) no[axis] = 1f else if (axis in 3..5) no[axis - 3] = -1f
                    val sp = convert(CleanRoomGltf.IDENTITY.copyOf(), 1, d.int("projAxisSpace"), ob, bone, null, null)
                    no = RigMath.dir(invert(sp), no)
                    val nl = RigMath.len(no)
                    if (nl < 1.1920929E-7f || !tree.hasFaces) fail = true else {
                        no = RigMath.scale(no, 1f / nl)
                        val hit = ShrinkTree.RayHit()
                        val lim = d.float("projLimit")
                        hit.dist = if (lim == 0f) Float.MAX_VALUE / 2f else lim
                        var cull = flag and 24
                        Shrinkwrap.projectNormal(cull, co, no, l2t, t2l, tree, hit)
                        if (flag and 1 != 0) {
                            if (flag and 2 != 0 && cull != 0) cull = cull xor 24
                            Shrinkwrap.projectNormal(cull, co, RigMath.scale(no, -1f), l2t, t2l, tree, hit)
                        }
                        if (hit.index < 0) fail = true else {
                            if (track) trackNo = ShrinkMath.normalized(RigMath.dir(t2l, tree.smoothNormal(hit.index, RigMath.point(l2t, hit.co), hit.no)))
                            co = ShrinkMath.snap(tree, l2t, t2l, mode, hit.index, hit.co, hit.no, dist, co)
                        }
                    }
                }
            }
            if (fail) co = floatArrayOf(0f, 0f, 0f)
            val p = RigMath.point(own, co)
            var res = own.copyOf().also { it[12] = p[0]; it[13] = p[1]; it[14] = p[2] }
            val tn = trackNo
            if (tn != null && track) res = ConstraintMath.dampTrackVec(res, RigMath.dir(own, tn), d.int("trackAxis"))
            return res
        }

        private fun actionConstraint(c: BlendConstraint, own: FloatArray, t0: FloatArray?, ob: BlendFile.View, bone: String?): FloatArray? {
            val d = c.data
            val flag = d.int("flag").let { if (f.version < 290) 0 else if (f.version < 300) it and 1 else it }
            val s = if (flag and 2 != 0) d.float("eval_time") else {
                val tm = t0 ?: return null
                val type = d.int("type")
                val vec: FloatArray
                val axis: Int
                if (type < 10) {
                    vec = RigMath.toEul(tm, 1).map { Math.toDegrees(it.toDouble()).toFloat() }.toFloatArray()
                    axis = type
                } else if (type < 20) {
                    vec = RigMath.size(tm)
                    axis = type - 10
                } else {
                    vec = RigMath.loc(tm)
                    axis = type - 20
                }
                val mn = d.float("min")
                val mx = d.float("max")
                if (mx == mn) 0f else (vec[axis.coerceIn(0, 2)] - mn) / (mx - mn)
            }.coerceIn(0f, 1f)
            val start = d.float("start")
            val end = d.float("end")
            val time = s * (end - start) + start
            val actAt = f.offsetOf(d.ptr("act")) ?: return null
            val act = action(actAt, if (d.has("action_slot_handle")) d.int("action_slot_handle").takeIf { it != 0 } else null) ?: return null
            fun v(path: String, i: Int, def: Float): Float = act.curves["$path#$i"]?.let { evaluate(it, time) } ?: def
            val useObject = bone == null || flag and 1 != 0
            val prefix = if (useObject) "" else "pose.bones[\"$bone\"]."
            val mode = rotOrderRaw(ob, if (useObject) null else bone)
            val loc = FloatArray(3) { v("${prefix}location", it, 0f) }
            val scale = FloatArray(3) { v("${prefix}scale", it, 1f) }
            val r = when {
                mode > 0 -> eulerMatrix(FloatArray(3) { v("${prefix}rotation_euler", it, 0f) }, mode)
                mode == -1 -> {
                    val aa = FloatArray(4) { v("${prefix}rotation_axis_angle", it, if (it == 2) 1f else 0f) }
                    axisAngle(aa[1], aa[2], aa[3], aa[0])
                }
                else -> quatMatrix(FloatArray(4) { v("${prefix}rotation_quaternion", it, if (it == 0) 1f else 0f) })
            }
            val am = CleanRoomGltf.mul(r, CleanRoomGltf.trs(null, null, scale))
            am[12] = loc[0]; am[13] = loc[1]; am[14] = loc[2]
            val mix = if (d.has("mix_mode")) d.int("mix_mode") else 0
            return when (mix) {
                1 -> RigMath.alignedScale(own, am)
                2 -> RigMath.alignedScale(am, own)
                3 -> RigMath.splitChannels(own, am)
                4 -> RigMath.splitChannels(am, own)
                5 -> CleanRoomGltf.mul(am, own)
                else -> CleanRoomGltf.mul(own, am)
            }
        }

        private fun followPath(c: BlendConstraint, own: FloatArray): FloatArray? {
            val tob = c.targets.firstOrNull()?.first?.let { owners[it] } ?: return null
            if (tob.int("type") != 2) return null
            val cu = curveData(tob) ?: return null
            val path = curvePath(tob) ?: return null
            val d = c.data
            val ff = d.int("followflag")
            var ct: Float
            if (ff and 2 == 0) {
                ct = prop(cu.at, "eval_time", 0) - d.float("offset")
                val pl = cu.int("pathlen")
                if (pl != 0) ct /= pl
                if (!path.cyclic && cu.int("flag") and 32 != 0) ct = ct.coerceIn(0f, 1f)
            } else ct = d.float("offset_fac")
            val smp = path.where(ct) ?: return null
            var tot = CleanRoomGltf.IDENTITY.copyOf()
            if (ff and 1 != 0) tot = RigMath.quatMat(BlendPath.applyTrack(smp.quat, d.int("trackflag"), d.int("upflag")))
            if (ff and 4 != 0) tot = CleanRoomGltf.mul(CleanRoomGltf.trs(null, null, floatArrayOf(smp.radius, smp.radius, smp.radius)), tot)
            tot[12] = smp.vec[0]; tot[13] = smp.vec[1]; tot[14] = smp.vec[2]
            val ct2 = CleanRoomGltf.mul(world(tob), tot)
            val size = RigMath.size(own)
            val r = CleanRoomGltf.mul(ct2, own)
            if (ff and 4 == 0) {
                val os = RigMath.size(r)
                for (k in 0 until 3) if (os[k] != 0f) for (q in 0 until 3) r[k * 4 + q] *= size[k] / os[k]
            }
            return r
        }

        private fun clampTo(c: BlendConstraint, own: FloatArray): FloatArray? {
            val tob = c.targets.firstOrNull()?.first?.let { owners[it] } ?: return null
            if (tob.int("type") != 2) return null
            val path = curvePath(tob) ?: return null
            val tw = world(tob)
            val mn = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
            val mx = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            for (p in path.points) {
                val w = transformPoint(tw, p.vec[0], p.vec[1], p.vec[2])
                for (k in 0 until 3) { mn[k] = minOf(mn[k], w[k]); mx[k] = maxOf(mx[k], w[k]) }
            }
            val d = c.data
            val flag = d.int("flag")
            val axis = if (flag == 0) {
                val sz = RigMath.sub(mx, mn)
                if (sz[2] > sz[0] && sz[2] > sz[1]) 2 else if (sz[1] > sz[0] && sz[1] > sz[2]) 1 else 0
            } else (flag - 1).coerceIn(0, 2)
            val loc = RigMath.loc(own)
            val len = mx[axis] - mn[axis]
            val ct = if (d.int("flag2") and 1 != 0) {
                if (abs(len) > 1e-6f) {
                    when {
                        loc[axis] < mn[axis] -> {
                            val off = mn[axis] - kotlin.math.ceil((mn[axis] - loc[axis]) / len) * len
                            (loc[axis] - off) / len
                        }
                        loc[axis] > mx[axis] -> {
                            val off = mx[axis] + ((loc[axis] - mx[axis]) / len).toInt() * len
                            (loc[axis] - off) / len
                        }
                        else -> (loc[axis] - mn[axis]) / len
                    }
                } else 0f
            } else when {
                loc[axis] <= mn[axis] -> 0f
                loc[axis] >= mx[axis] -> 1f
                abs(len) > 1e-6f -> (loc[axis] - mn[axis]) / len
                else -> 0f
            }
            val smp = path.where(ct) ?: return null
            val p = transformPoint(tw, smp.vec[0], smp.vec[1], smp.vec[2])
            return RigMath.withLoc(own, p)
        }

        private fun rotOrderRaw(ob: BlendFile.View, bone: String?): Int =
            if (bone != null) poseChannels(ob).firstOrNull { it.str("name") == bone }?.int("rotmode", 0) ?: 0 else ob.int("rotmode", 1)

        private fun armatureConstraint(c: BlendConstraint, own: FloatArray, ob: BlendFile.View, bone: String?): FloatArray? {
            val flag = c.data.int("flag")
            val input = if (bone != null && flag and 4 == 0) {
                val r = bones(ob)[bone]
                if (r != null) transformPoint(world(ob), r.head[0], r.head[1], r.head[2]) else RigMath.loc(own)
            } else RigMath.loc(own)
            val sum = FloatArray(16)
            var total = 0f
            for ((i, t) in c.targets.withIndex()) {
                val w0 = c.weights.getOrElse(i) { 1f }
                if (w0 <= 0f) continue
                val tar = t.first?.let { owners[it] } ?: return own
                if (tar.int("type") != 25) return own
                val r = bones(tar)[t.second] ?: return own
                val pm = bonePose(tar, t.second) ?: return own
                val tw = world(tar)
                val co = transformPoint(invert(tw), input[0], input[1], input[2])
                var w = w0
                if (flag and 2 != 0 || r.flag and (1 shl 11) != 0) w *= RigMath.distFactor(co, r)
                if (w <= 0f) continue
                val m = CleanRoomGltf.mul(tw, CleanRoomGltf.mul(CleanRoomGltf.mul(pm, invert(r.armMat)), invert(tw)))
                for (k in 0 until 16) sum[k] += w * m[k]
                total += w
            }
            if (total <= 0f) return own
            for (k in 0 until 16) sum[k] /= total
            return CleanRoomGltf.mul(sum, own)
        }

        fun targetMatrix(t: Target): FloatArray? {
            val ob = t.id?.let { owners[it] } ?: return null
            if (ob.struct.type != "Object") return null
            val local = t.flag and 4 != 0
            return if (t.pchan.isNotEmpty() && ob.int("type") == 25) {
                if (local) channelLocal(ob, t.pchan) else CleanRoomGltf.mul(world(ob), bonePose(ob, t.pchan) ?: CleanRoomGltf.IDENTITY)
            } else if (local) objectLocal(ob) else world(ob)
        }

        fun channelValue(t: Target): Float {
            val ob = t.id?.let { owners[it] } ?: return 0f
            val m = targetMatrix(t) ?: return 0f
            val chan = t.chan
            val local = t.flag and 4 != 0
            return when (chan) {
                0, 1, 2 -> m[12 + chan]
                6, 7, 8 -> axisLength(m, chan - 6)
                10 -> (axisLength(m, 0) + axisLength(m, 1) + axisLength(m, 2)) / 3f
                3, 4, 5, 9 -> {
                    val ownMode = if (t.pchan.isNotEmpty()) poseChannels(ob).firstOrNull { it.str("name") == t.pchan }?.int("rotmode", 0) ?: 0 else ob.int("rotmode", 1)
                    val mode = when {
                        t.rotMode in 1..6 -> t.rotMode
                        t.rotMode == 0 && ownMode in 1..6 -> ownMode
                        t.rotMode == 0 -> 1
                        else -> -2
                    }
                    if (mode == -2 || chan == 9) {
                        val q = matToQuat(normalizedAxes(m))
                        when (chan) { 9 -> q[0]; 3 -> q[1]; 4 -> q[2]; else -> q[3] }
                    } else if (local && t.rotMode == 0 && ownMode in 1..6) {
                        val prefix = if (t.pchan.isNotEmpty()) "pose.bones[\"${t.pchan}\"]." else ""
                        prop(ob.at, "${prefix}rotation_euler", chan - 3)
                    } else matToEuler(normalizedAxes(m), mode)[chan - 3]
                }
                else -> 0f
            }
        }

        fun singleProp(t: Target): Float {
            val id = t.id ?: return 0f
            var path = t.path
            var index = 0
            val m = Regex("""\[(\d+)]$""").find(path)
            if (m != null) {
                index = m.groupValues[1].toInt()
                path = path.substring(0, m.range.first)
            }
            if (path.isEmpty() || path.startsWith("[")) return 0f
            return prop(id, path, index)
        }
    }

    private fun evalDriver(d: Driver, fr: Frame): Float {
        val values = d.vars.map { v ->
            when (v.type) {
                0 -> v.targets.firstOrNull()?.let { fr.singleProp(it) } ?: 0f
                3 -> v.targets.firstOrNull()?.let { fr.channelValue(it) } ?: 0f
                2 -> {
                    val a = v.targets.getOrNull(0)?.let { fr.targetMatrix(it) }
                    val b = v.targets.getOrNull(1)?.let { fr.targetMatrix(it) }
                    if (a == null || b == null) 0f else {
                        val dx = a[12] - b[12]
                        val dy = a[13] - b[13]
                        val dz = a[14] - b[14]
                        sqrt(dx * dx + dy * dy + dz * dz)
                    }
                }
                1 -> {
                    val a = v.targets.getOrNull(0)?.let { fr.targetMatrix(it) }
                    val b = v.targets.getOrNull(1)?.let { fr.targetMatrix(it) }
                    if (a == null || b == null) 0f else {
                        val qa = matToQuat(normalizedAxes(a))
                        val qb = matToQuat(normalizedAxes(b))
                        val dot = abs(qa[0] * qb[0] + qa[1] * qb[1] + qa[2] * qb[2] + qa[3] * qb[3]).coerceAtMost(1f)
                        2f * acos(dot)
                    }
                }
                else -> 0f
            }
        }
        val raw = when (d.type) {
            0 -> if (values.isEmpty()) 0f else values.sum() / values.size
            2 -> values.sum()
            3 -> values.minOrNull() ?: 0f
            4 -> values.maxOrNull() ?: 0f
            else -> {
                val env = HashMap<String, Double>()
                for ((i, v) in d.vars.withIndex()) env[v.name] = values[i].toDouble()
                env["frame"] = fr.frame.toDouble()
                DriverExpression(d.expr, env).evaluate()?.toFloat() ?: d.curval
            }
        }
        val c = d.curve
        return if (c.keys.isEmpty() && c.mods.isEmpty()) raw else evaluate(c, raw)
    }

    private fun cbrtAbs(v: Float): Float = kotlin.math.cbrt(kotlin.math.abs(v))

    private fun mat4(v: BlendFile.View, n: String): FloatArray? {
        val m = v.floats(n, 16) ?: return null
        if (m.all { it == 0f }) return null
        return m
    }

    companion object {
        private fun easeOut(ipo: Int, t: Float, k: Kf): Float = when (ipo) {
            3 -> { val s = k.back; val x = t - 1f; x * x * ((s + 1) * x + s) + 1f }
            4 -> bounceOut(t)
            5 -> sqrt(maxOf(0f, 1f - (t - 1f) * (t - 1f)))
            6 -> { val x = t - 1f; x * x * x + 1f }
            7 -> {
                if (t <= 0f || t >= 1f) t.coerceIn(0f, 1f) else {
                    val p = (k.period.takeIf { it > 0f } ?: 0.3f) / 10f
                    val a = maxOf(1f, k.amplitude)
                    val s = p / (2 * PI.toFloat()) * asin(1f / a)
                    a * 2f.pow(-10f * t) * sin((t - s) * (2 * PI.toFloat()) / p) + 1f
                }
            }
            8 -> if (t >= 1f) 1f else 1f - 2f.pow(-10f * t)
            9 -> -t * (t - 2f)
            10 -> { val x = t - 1f; 1f - x * x * x * x }
            11 -> { val x = t - 1f; x * x * x * x * x + 1f }
            12 -> sin(t * PI.toFloat() / 2f)
            else -> t
        }

        private fun bounceOut(t: Float): Float = when {
            t < 1f / 2.75f -> 7.5625f * t * t
            t < 2f / 2.75f -> { val x = t - 1.5f / 2.75f; 7.5625f * x * x + 0.75f }
            t < 2.5f / 2.75f -> { val x = t - 2.25f / 2.75f; 7.5625f * x * x + 0.9375f }
            else -> { val x = t - 2.625f / 2.75f; 7.5625f * x * x + 0.984375f }
        }

        private fun ease(ipo: Int, mode: Int, t: Float, k: Kf): Float {
            val m = if (mode == 0) (if (ipo == 3 || ipo == 4 || ipo == 7) 2 else 1) else mode
            return when (m) {
                1 -> 1f - easeOut(ipo, 1f - t, k)
                2 -> easeOut(ipo, t, k)
                else -> if (t < 0.5f) (1f - easeOut(ipo, 1f - 2f * t, k)) / 2f else (easeOut(ipo, 2f * t - 1f, k) + 1f) / 2f
            }
        }

        fun evaluate(c: Curve, frame: Float): Float {
            var x = frame
            var offset = 0f
            val keys = c.keys
            val n = keys.size
            for (m in c.mods) {
                if (m.type == 9) {
                    val step = m.vals[0].takeIf { it > 0f } ?: continue
                    if (m.a and 1 != 0 && x < m.vals[2]) continue
                    if (m.a and 2 != 0 && x > m.vals[3]) continue
                    x = floor((x - m.vals[1]) / step) * step + m.vals[1]
                }
            }
            val cyc = c.mods.firstOrNull { it.type == 4 }
            if (cyc != null && n >= 2) {
                val first = keys[0].x
                val last = keys[n - 1].x
                val span = last - first
                if (span > 0f) {
                    val after = x > last
                    val before = x < first
                    val mode = if (after) cyc.b else if (before) cyc.a else 0
                    val limit = if (after) cyc.d else cyc.c
                    val dist = if (after) (x - last) / span else (first - x) / span
                    if (mode != 0 && (limit <= 0 || dist < limit)) {
                        val k = floor((x - first) / span)
                        val p = (x - first) - k * span
                        x = if (mode == 3 && abs(k.toInt()) % 2 == 1) last - p else first + p
                        if (mode == 2) offset = k * (keys[n - 1].y - keys[0].y)
                    }
                }
            }
            var v = if (n == 0) frame else sampleKeys(c, x)
            for (m in c.mods) {
                when (m.type) {
                    1 -> {
                        val input = if (n == 0) frame else x
                        val g = generator(m, input)
                        v = if (n > 0 && m.b and 1 != 0) v + g else g
                    }
                    8 -> {
                        if (m.a and 4 != 0) v = maxOf(v, m.vals[1])
                        if (m.a and 8 != 0) v = minOf(v, m.vals[3])
                    }
                }
            }
            v += offset
            return if (c.round) floor(v + 0.5f) else v
        }

        private fun generator(m: Mod, x: Float): Float {
            val co = m.vals
            if (co.isEmpty()) return 0f
            return if (m.a == 0) {
                var r = 0f
                var p = 1f
                for (c in co) {
                    r += c * p
                    p *= x
                }
                r
            } else {
                var r = 1f
                var i = 0
                while (i + 1 < co.size) {
                    r *= co[i] * x + co[i + 1]
                    i += 2
                }
                r
            }
        }

        private fun sampleKeys(c: Curve, x: Float): Float {
            val keys = c.keys
            val n = keys.size
            if (n == 1) return keys[0].y
            val first = keys[0].x
            val last = keys[n - 1].x
            if (x <= first) {
                val k0 = keys[0]
                if (c.extend != 1 || k0.ipo == 0) return k0.y
                val slope = if (k0.ipo == 2) {
                    val dx = k0.x - k0.lx
                    if (dx != 0f) (k0.y - k0.ly) / dx else 0f
                } else {
                    val dx = keys[1].x - k0.x
                    if (dx != 0f) (keys[1].y - k0.y) / dx else 0f
                }
                return k0.y + slope * (x - first)
            }
            if (x >= last) {
                val kn = keys[n - 1]
                if (c.extend != 1 || kn.ipo == 0) return kn.y
                val slope = if (kn.ipo == 2) {
                    val dx = kn.rx - kn.x
                    if (dx != 0f) (kn.ry - kn.y) / dx else 0f
                } else {
                    val dx = kn.x - keys[n - 2].x
                    if (dx != 0f) (kn.y - keys[n - 2].y) / dx else 0f
                }
                return kn.y + slope * (x - last)
            }
            var lo = 0
            var hi = n - 1
            while (hi - lo > 1) {
                val mid = (lo + hi) ushr 1
                if (keys[mid].x <= x) lo = mid else hi = mid
            }
            val a = keys[lo]
            val b = keys[hi]
            val dx = b.x - a.x
            if (dx <= 0f) return b.y
            val t = (x - a.x) / dx
            return when (a.ipo) {
                0 -> a.y
                1 -> a.y + (b.y - a.y) * t
                2 -> bezier(a, b, x)
                else -> a.y + (b.y - a.y) * ease(a.ipo, a.easing, t, a)
            }
        }

        private fun bezier(a: Kf, b: Kf, x: Float): Float {
            val p0x = a.x
            val p3x = b.x
            val len = p3x - p0x
            var p1x = a.rx
            var p2x = b.lx
            var p1y = a.ry
            var p2y = b.ly
            val h1 = abs(a.x - a.rx)
            val h2 = abs(b.x - b.lx)
            if (h1 > len && h1 > 0f) {
                val fac = len / h1
                p1x = a.x - (a.x - a.rx) * fac
                p1y = a.y - (a.y - a.ry) * fac
            }
            if (h2 > len && h2 > 0f) {
                val fac = len / h2
                p2x = b.x - (b.x - b.lx) * fac
                p2y = b.y - (b.y - b.ly) * fac
            }
            var lo = 0f
            var hi = 1f
            var s = 0.5f
            repeat(48) {
                s = (lo + hi) / 2
                val u = 1 - s
                val bx = u * u * u * p0x + 3 * u * u * s * p1x + 3 * u * s * s * p2x + s * s * s * p3x
                if (bx < x) lo = s else hi = s
            }
            val u = 1 - s
            return u * u * u * a.y + 3 * u * u * s * p1y + 3 * u * s * s * p2y + s * s * s * b.y
        }

        fun invert(m: FloatArray): FloatArray {
            val d = UsdMath.invert(DoubleArray(16) { m[it].toDouble() }) ?: return CleanRoomGltf.IDENTITY
            return FloatArray(16) { d[it].toFloat() }
        }

        fun transformPoint(m: FloatArray, x: Float, y: Float, z: Float): FloatArray = floatArrayOf(
            m[0] * x + m[4] * y + m[8] * z + m[12],
            m[1] * x + m[5] * y + m[9] * z + m[13],
            m[2] * x + m[6] * y + m[10] * z + m[14]
        )

        fun axisLength(m: FloatArray, c: Int): Float = sqrt(m[c * 4] * m[c * 4] + m[c * 4 + 1] * m[c * 4 + 1] + m[c * 4 + 2] * m[c * 4 + 2])

        fun normalizedAxes(m: FloatArray): FloatArray {
            val r = m.copyOf()
            for (c in 0 until 3) {
                val l = axisLength(m, c)
                if (l > 0f) for (k in 0 until 3) r[c * 4 + k] = m[c * 4 + k] / l
            }
            return r
        }

        fun quatMatrix(q: FloatArray): FloatArray {
            val l = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
            if (l <= 0f) return CleanRoomGltf.IDENTITY.copyOf()
            return CleanRoomGltf.trs(null, floatArrayOf(q[1] / l, q[2] / l, q[3] / l, q[0] / l), null)
        }

        fun axisAngle(x: Float, y: Float, z: Float, angle: Float): FloatArray {
            val l = sqrt(x * x + y * y + z * z)
            if (l <= 0f) return CleanRoomGltf.IDENTITY.copyOf()
            val s = sin(angle / 2)
            return CleanRoomGltf.trs(null, floatArrayOf(x / l * s, y / l * s, z / l * s, cos(angle / 2)), null)
        }

        fun eulerMatrix(e: FloatArray, mode: Int): FloatArray {
            fun axis(i: Int, a: Float): FloatArray {
                val c = cos(a)
                val s = sin(a)
                val m = CleanRoomGltf.IDENTITY.copyOf()
                when (i) {
                    0 -> { m[5] = c; m[6] = s; m[9] = -s; m[10] = c }
                    1 -> { m[0] = c; m[2] = -s; m[8] = s; m[10] = c }
                    else -> { m[0] = c; m[1] = s; m[4] = -s; m[5] = c }
                }
                return m
            }
            val x = axis(0, e[0])
            val y = axis(1, e[1])
            val z = axis(2, e[2])
            val m = CleanRoomGltf::mul
            return when (mode) {
                2 -> m(y, m(z, x))
                3 -> m(z, m(x, y))
                4 -> m(x, m(z, y))
                5 -> m(y, m(x, z))
                6 -> m(x, m(y, z))
                else -> m(z, m(y, x))
            }
        }

        private val orders = arrayOf(intArrayOf(0, 1, 2, 0), intArrayOf(0, 2, 1, 1), intArrayOf(1, 0, 2, 1), intArrayOf(1, 2, 0, 0), intArrayOf(2, 0, 1, 0), intArrayOf(2, 1, 0, 1))

        fun matToEuler(m: FloatArray, mode: Int): FloatArray {
            val o = orders[(mode - 1).coerceIn(0, 5)]
            val i = o[0]
            val j = o[1]
            val k = o[2]
            fun a(c: Int, r: Int) = m[c * 4 + r]
            val e = FloatArray(3)
            val cy = hypot(a(i, i), a(i, j))
            if (cy > 16f * 1.1920929E-7f) {
                e[i] = atan2(a(j, k), a(k, k))
                e[j] = atan2(-a(i, k), cy)
                e[k] = atan2(a(i, j), a(i, i))
            } else {
                e[i] = atan2(-a(k, j), a(j, j))
                e[j] = atan2(-a(i, k), cy)
                e[k] = 0f
            }
            if (o[3] == 1) for (n in 0 until 3) e[n] = -e[n]
            return e
        }

        fun matToQuat(m: FloatArray): FloatArray {
            val tr = m[0] + m[5] + m[10]
            val q = FloatArray(4)
            if (tr > 0f) {
                val s = sqrt(tr + 1f) * 2f
                q[0] = 0.25f * s
                q[1] = (m[6] - m[9]) / s
                q[2] = (m[8] - m[2]) / s
                q[3] = (m[1] - m[4]) / s
            } else if (m[0] > m[5] && m[0] > m[10]) {
                val s = sqrt(1f + m[0] - m[5] - m[10]) * 2f
                q[0] = (m[6] - m[9]) / s
                q[1] = 0.25f * s
                q[2] = (m[4] + m[1]) / s
                q[3] = (m[8] + m[2]) / s
            } else if (m[5] > m[10]) {
                val s = sqrt(1f + m[5] - m[0] - m[10]) * 2f
                q[0] = (m[8] - m[2]) / s
                q[1] = (m[4] + m[1]) / s
                q[2] = 0.25f * s
                q[3] = (m[9] + m[6]) / s
            } else {
                val s = sqrt(1f + m[10] - m[0] - m[5]) * 2f
                q[0] = (m[1] - m[4]) / s
                q[1] = (m[8] + m[2]) / s
                q[2] = (m[9] + m[6]) / s
                q[3] = 0.25f * s
            }
            return q
        }
    }
}

internal class DriverExpression(private val s: String, private val env: Map<String, Double>) {
    private var i = 0

    fun evaluate(): Double? = try {
        val v = ternary()
        skip()
        if (i < s.length || !v.isFinite()) null else v
    } catch (_: Exception) {
        null
    }

    private fun skip() {
        while (i < s.length && s[i].isWhitespace()) i++
    }

    private fun eat(t: String): Boolean {
        skip()
        if (!s.startsWith(t, i)) return false
        i += t.length
        return true
    }

    private fun word(w: String): Boolean {
        skip()
        if (!s.startsWith(w, i)) return false
        val e = i + w.length
        if (e < s.length && (s[e].isLetterOrDigit() || s[e] == '_')) return false
        i = e
        return true
    }

    private fun ternary(): Double {
        val a = or()
        if (word("if")) {
            val c = or()
            if (!word("else")) throw IllegalStateException()
            val b = ternary()
            return if (c != 0.0) a else b
        }
        return a
    }

    private fun or(): Double {
        var a = and()
        while (word("or")) {
            val b = and()
            a = if (a != 0.0) a else b
        }
        return a
    }

    private fun and(): Double {
        var a = not()
        while (word("and")) {
            val b = not()
            a = if (a == 0.0) a else b
        }
        return a
    }

    private fun not(): Double = if (word("not")) (if (not() == 0.0) 1.0 else 0.0) else cmp()

    private fun cmp(): Double {
        var left = sum()
        var result = true
        var chained = false
        while (true) {
            val op = when {
                eat("<=") -> 0
                eat(">=") -> 1
                eat("==") -> 2
                eat("!=") -> 3
                eat("<") -> 4
                eat(">") -> 5
                else -> return if (chained) (if (result) 1.0 else 0.0) else left
            }
            val right = sum()
            val ok = when (op) {
                0 -> left <= right
                1 -> left >= right
                2 -> left == right
                3 -> left != right
                4 -> left < right
                else -> left > right
            }
            result = result && ok
            chained = true
            left = right
        }
    }

    private fun sum(): Double {
        var a = term()
        while (true) {
            a = when {
                eat("+") -> a + term()
                eat("-") -> a - term()
                else -> return a
            }
        }
    }

    private fun term(): Double {
        var a = unary()
        while (true) {
            skip()
            if (s.startsWith("**", i)) return a
            a = when {
                eat("//") -> floor(a / unary())
                eat("*") -> a * unary()
                eat("/") -> a / unary()
                eat("%") -> { val b = unary(); a - b * floor(a / b) }
                else -> return a
            }
        }
    }

    private fun unary(): Double = when {
        eat("-") -> -unary()
        eat("+") -> unary()
        else -> power()
    }

    private fun power(): Double {
        val a = atom()
        if (eat("**")) return a.pow(unary())
        return a
    }

    private fun atom(): Double {
        skip()
        if (i >= s.length) throw IllegalStateException()
        if (eat("(")) {
            val v = ternary()
            if (!eat(")")) throw IllegalStateException()
            return v
        }
        val c = s[i]
        if (c.isDigit() || c == '.') {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                while (i < s.length && s[i].isDigit()) i++
            }
            return s.substring(st, i).toDouble()
        }
        if (c.isLetter() || c == '_') {
            val st = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '.')) i++
            val name = s.substring(st, i).removePrefix("math.")
            if (eat("(")) {
                val args = ArrayList<Double>()
                if (!eat(")")) {
                    do args.add(ternary()) while (eat(","))
                    if (!eat(")")) throw IllegalStateException()
                }
                return call(name, args)
            }
            env[name]?.let { return it }
            return when (name) {
                "pi" -> PI
                "e" -> Math.E
                "tau" -> 2 * PI
                "True" -> 1.0
                "False" -> 0.0
                else -> throw IllegalStateException()
            }
        }
        throw IllegalStateException()
    }

    private fun call(n: String, a: List<Double>): Double {
        fun x(k: Int) = a.getOrElse(k) { throw IllegalStateException() }
        return when (n) {
            "sin" -> sin(x(0))
            "cos" -> cos(x(0))
            "tan" -> kotlin.math.tan(x(0))
            "asin" -> asin(x(0))
            "acos" -> acos(x(0))
            "atan" -> kotlin.math.atan(x(0))
            "atan2" -> atan2(x(0), x(1))
            "hypot" -> hypot(x(0), x(1))
            "sqrt" -> sqrt(x(0))
            "abs", "fabs" -> abs(x(0))
            "exp" -> kotlin.math.exp(x(0))
            "log" -> if (a.size > 1) kotlin.math.ln(x(0)) / kotlin.math.ln(x(1)) else kotlin.math.ln(x(0))
            "log10" -> kotlin.math.log10(x(0))
            "pow" -> x(0).pow(x(1))
            "floor" -> floor(x(0))
            "ceil" -> kotlin.math.ceil(x(0))
            "trunc", "int" -> kotlin.math.truncate(x(0))
            "round" -> kotlin.math.round(x(0))
            "float" -> x(0)
            "bool" -> if (x(0) != 0.0) 1.0 else 0.0
            "radians" -> Math.toRadians(x(0))
            "degrees" -> Math.toDegrees(x(0))
            "min" -> a.minOrNull() ?: throw IllegalStateException()
            "max" -> a.maxOrNull() ?: throw IllegalStateException()
            "clamp" -> x(0).coerceIn(a.getOrElse(1) { 0.0 }, a.getOrElse(2) { 1.0 })
            "lerp" -> x(0) + (x(1) - x(0)) * x(2)
            "smoothstep" -> {
                val t = ((x(2) - x(0)) / (x(1) - x(0))).coerceIn(0.0, 1.0)
                t * t * (3 - 2 * t)
            }
            else -> throw IllegalStateException()
        }
    }
}
