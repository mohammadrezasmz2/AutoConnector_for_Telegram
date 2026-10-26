package io.autoconnector.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * The historical Iranian Lion and Sun flag (green / white / red with the lion
 * holding a sword before a rising sun), drawn natively — a simplified emblem
 * that stays legible at title-bar size.
 */
@Composable
fun LionSunFlag(width: Dp = 36.dp, height: Dp = 24.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width, height)) {
        val w = size.width
        val h = size.height
        val u = h / 3f
        val corner = CornerRadius(h * 0.08f, h * 0.08f)
        // Stripes.
        drawRoundRect(Color(0xFFDA0000), size = Size(w, h), cornerRadius = corner)
        drawRect(Color.White, topLeft = Offset(0f, u), size = Size(w, u))
        drawRect(Color(0xFF239F40), topLeft = Offset(0f, 0f), size = Size(w, u))
        val cx = w / 2f
        val cy = h / 2f
        val gold = Color(0xFFF2B705)
        val lion = Color(0xFFC98516)
        // Sun rising behind the lion's back, with rays.
        val sun = Offset(cx + 0.18f * u, cy - 0.12f * u)
        for (i in 0 until 12) {
            val a = (i * 30.0) * kotlin.math.PI / 180.0
            val r1 = 0.30f * u
            val r2 = 0.44f * u
            drawLine(
                gold,
                Offset(sun.x + (r1 * cos(a)).toFloat(), sun.y + (r1 * sin(a)).toFloat()),
                Offset(sun.x + (r2 * cos(a)).toFloat(), sun.y + (r2 * sin(a)).toFloat()),
                strokeWidth = u * 0.05f,
                cap = StrokeCap.Round,
            )
        }
        drawCircle(gold, radius = 0.28f * u, center = sun)
        // Lion: body, head with mane, legs, tail.
        drawRoundRect(
            lion,
            topLeft = Offset(cx - 0.42f * u, cy - 0.02f * u),
            size = Size(0.78f * u, 0.26f * u),
            cornerRadius = CornerRadius(0.12f * u, 0.12f * u),
        )
        drawCircle(lion, radius = 0.17f * u, center = Offset(cx - 0.44f * u, cy - 0.10f * u))
        val legW = 0.07f * u
        for (lx in floatArrayOf(-0.38f, -0.22f, 0.12f, 0.27f)) {
            drawRect(lion, topLeft = Offset(cx + lx * u, cy + 0.2f * u), size = Size(legW, 0.24f * u))
        }
        val tail = Path().apply {
            moveTo(cx + 0.34f * u, cy + 0.04f * u)
            quadraticBezierTo(cx + 0.58f * u, cy - 0.05f * u, cx + 0.50f * u, cy - 0.32f * u)
        }
        drawPath(tail, lion, style = Stroke(width = u * 0.06f, cap = StrokeCap.Round))
        // Raised sword.
        drawLine(
            Color(0xFF8A8F98),
            Offset(cx - 0.56f * u, cy + 0.02f * u),
            Offset(cx - 0.74f * u, cy - 0.44f * u),
            strokeWidth = u * 0.06f,
            cap = StrokeCap.Round,
        )
    }
}
