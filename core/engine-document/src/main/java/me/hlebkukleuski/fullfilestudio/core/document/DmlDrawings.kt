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

package me.hlebkukleuski.fullfilestudio.core.document

class DmlCellAnchor(
    val kind: Int,
    val fromCol: Int,
    val fromColOff: Long,
    val fromRow: Int,
    val fromRowOff: Long,
    val toCol: Int,
    val toColOff: Long,
    val toRow: Int,
    val toRowOff: Long,
    val x: Long,
    val y: Long,
    val cx: Long,
    val cy: Long,
    val element: DmlNode,
    val drawingPath: String
) {
    companion object {
        const val TWO_CELL = 0
        const val ONE_CELL = 1
        const val ABSOLUTE = 2
    }
}

object DmlDrawings {
    private val ELEMENTS = setOf("sp", "pic", "graphicFrame", "grpSp", "cxnSp")

    fun forSheet(pkg: DmlPackage, sheetPath: String): List<DmlCellAnchor> {
        val out = ArrayList<DmlCellAnchor>()
        for (rel in pkg.rels(sheetPath).values) {
            if (rel.external || !rel.type.endsWith("/drawing")) continue
            out.addAll(parse(pkg, rel.target))
        }
        return out
    }

    fun parse(pkg: DmlPackage, drawingPath: String): List<DmlCellAnchor> {
        val root = pkg.xml(drawingPath) ?: return emptyList()
        val out = ArrayList<DmlCellAnchor>()
        for (a in root.children) {
            if (out.size >= 2000) break
            val kind = when (a.name) {
                "twoCellAnchor" -> DmlCellAnchor.TWO_CELL
                "oneCellAnchor" -> DmlCellAnchor.ONE_CELL
                "absoluteAnchor" -> DmlCellAnchor.ABSOLUTE
                else -> continue
            }
            val el = a.children.firstOrNull { it.name in ELEMENTS } ?: continue
            val from = a.child("from")
            val to = a.child("to")
            val ext = a.child("ext")
            val pos = a.child("pos")
            fun num(n: DmlNode?, name: String): Long = n?.child(name)?.text?.trim()?.toLongOrNull() ?: 0L
            out.add(
                DmlCellAnchor(
                    kind,
                    num(from, "col").toInt().coerceIn(0, 16384), num(from, "colOff"),
                    num(from, "row").toInt().coerceIn(0, 1_048_576), num(from, "rowOff"),
                    num(to, "col").toInt().coerceIn(0, 16384), num(to, "colOff"),
                    num(to, "row").toInt().coerceIn(0, 1_048_576), num(to, "rowOff"),
                    pos?.long("x") ?: 0L, pos?.long("y") ?: 0L,
                    ext?.long("cx") ?: 0L, ext?.long("cy") ?: 0L,
                    el, drawingPath
                )
            )
        }
        return out
    }
}
