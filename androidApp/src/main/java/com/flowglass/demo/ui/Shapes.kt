package com.flowglass.demo.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Cubic
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.circle
import androidx.graphics.shapes.star

/**
 * Material 3 Expressive style shapes, rebuilt with androidx.graphics.shapes.
 * All polygons are normalized into the unit square (0..1).
 */
object M3EShapes {
    val Cookie9: RoundedPolygon = RoundedPolygon.star(
        numVerticesPerRadius = 9,
        innerRadius = 0.8f,
        rounding = CornerRounding(0.5f),
    ).normalized()

    val Sunny: RoundedPolygon = RoundedPolygon.star(
        numVerticesPerRadius = 12,
        innerRadius = 0.84f,
        rounding = CornerRounding(0.12f),
    ).normalized()

    val Clover4: RoundedPolygon = RoundedPolygon.star(
        numVerticesPerRadius = 4,
        innerRadius = 0.42f,
        rounding = CornerRounding(0.42f),
        innerRounding = CornerRounding(0.08f),
    ).normalized()

    val Circle: RoundedPolygon = RoundedPolygon.circle(numVertices = 12).normalized()

    val CookieToSunny = Morph(Cookie9, Sunny)
    val CookieToCircle = Morph(Cookie9, Circle)
    val SunnyToCircle = Morph(Sunny, Circle)
}

fun List<Cubic>.toComposePath(size: Size, path: Path = Path()): Path {
    path.reset()
    if (isEmpty()) return path
    val w = size.width
    val h = size.height
    val first = first()
    path.moveTo(first.anchor0X * w, first.anchor0Y * h)
    for (c in this) {
        path.cubicTo(
            c.control0X * w, c.control0Y * h,
            c.control1X * w, c.control1Y * h,
            c.anchor1X * w, c.anchor1Y * h,
        )
    }
    path.close()
    return path
}

class PolygonShape(private val polygon: RoundedPolygon) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(polygon.cubics.toComposePath(size))
}
