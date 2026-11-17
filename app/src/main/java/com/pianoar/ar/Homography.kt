package com.pianoar.ar

import kotlin.math.abs

/**
 * Perspective mapping from keyboard-plane coordinates (u along the keys 0..1, v depth 0 = front .. 1 = back)
 * to normalized image coordinates (0..1).
 */
class Homography private constructor(private val h: DoubleArray) {

    /** Writes (x, y) into out and returns w (perspective depth; <= 0 means behind the camera / horizon). */
    fun map(u: Double, v: Double, out: FloatArray): Double {
        val w = h[6] * u + h[7] * v + 1.0
        if (abs(w) < 1e-9) return 0.0
        out[0] = ((h[0] * u + h[1] * v + h[2]) / w).toFloat()
        out[1] = ((h[3] * u + h[4] * v + h[5]) / w).toFloat()
        return w
    }

    companion object {
        /**
         * pts = x0,y0, x1,y1, x2,y2, x3,y3 for (u,v) = (0,0) front-left, (1,0) front-right,
         * (1,1) back-right, (0,1) back-left.
         */
        fun fromUnitSquare(pts: FloatArray): Homography? {
            val src = doubleArrayOf(0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0)
            val a = Array(8) { DoubleArray(9) }
            for (i in 0 until 4) {
                val u = src[2 * i]; val v = src[2 * i + 1]
                val x = pts[2 * i].toDouble(); val y = pts[2 * i + 1].toDouble()
                a[2 * i] = doubleArrayOf(u, v, 1.0, 0.0, 0.0, 0.0, -u * x, -v * x, x)
                a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, u, v, 1.0, -u * y, -v * y, y)
            }
            // Gaussian elimination with partial pivoting
            for (col in 0 until 8) {
                var piv = col
                for (r in col + 1 until 8) if (abs(a[r][col]) > abs(a[piv][col])) piv = r
                if (abs(a[piv][col]) < 1e-12) return null
                val tmp = a[col]; a[col] = a[piv]; a[piv] = tmp
                for (r in 0 until 8) if (r != col) {
                    val f = a[r][col] / a[col][col]
                    for (c in col until 9) a[r][c] -= f * a[col][c]
                }
            }
            val h = DoubleArray(9)
            for (i in 0 until 8) h[i] = a[i][8] / a[i][i]
            h[8] = 1.0
            return Homography(h)
        }
    }
}
