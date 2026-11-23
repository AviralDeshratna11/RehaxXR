package com.creatofit.motion.core

import kotlin.math.*

data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun unaryMinus() = V3(-x, -y, -z)
    operator fun plus(b: V3) = V3(x + b.x, y + b.y, z + b.z)
    operator fun minus(b: V3) = V3(x - b.x, y - b.y, z - b.z)
    operator fun times(s: Float) = V3(x * s, y * s, z * s)
    fun dot(b: V3) = x * b.x + y * b.y + z * b.z
    fun cross(b: V3) = V3(y * b.z - z * b.y, z * b.x - x * b.z, x * b.y - y * b.x)
    fun length() = sqrt(dot(this))
    fun unit() = this * (1f / length().coerceAtLeast(1e-6f))
    fun finite() = x.isFinite() && y.isFinite() && z.isFinite()
    /** Component of this vector perpendicular to the unit vector [n], normalised. */
    fun orthoTo(n: V3) = (this - n * dot(n)).unit()
    fun lerp(b: V3, t: Float) = this + (b - this) * t

    companion object {
        val ZERO = V3(0f, 0f, 0f)
        val UP = V3(0f, 1f, 0f)
    }
}

/** Angle ABC in degrees. */
fun angle(a: V3, b: V3, c: V3): Float {
    val u = a - b; val v = c - b
    if (u.length() < 1e-5f || v.length() < 1e-5f) return 0f
    return Math.toDegrees(acos(u.unit().dot(v.unit()).coerceIn(-1f, 1f)).toDouble()).toFloat()
}

/** Angle between two directions in degrees. */
fun angleBetween(a: V3, b: V3): Float =
    Math.toDegrees(acos(a.unit().dot(b.unit()).coerceIn(-1f, 1f)).toDouble()).toFloat()

/** Rodrigues rotation of [v] about unit [axis]. */
fun rotate(v: V3, axis: V3, radians: Float): V3 {
    val k = axis.unit(); val c = cos(radians); val s = sin(radians)
    return v * c + k.cross(v) * s + k * (k.dot(v) * (1 - c))
}

/** Rotate [v] toward [target] (perpendicular-ish) by [degrees]. */
fun tilt(v: V3, target: V3, degrees: Float): V3 {
    val axis = v.cross(target)
    if (axis.length() < 1e-6f) return v
    return rotate(v, axis, Math.toRadians(degrees.toDouble()).toFloat())
}

/** Orthonormal rotation stored as three column axes. */
data class Basis(val x: V3, val y: V3, val z: V3) {
    /** World vector from local coordinates. */
    fun toWorld(l: V3) = x * l.x + y * l.y + z * l.z
    /** Local coordinates of a world vector. */
    fun toLocal(w: V3) = V3(w.dot(x), w.dot(y), w.dot(z))
    fun matrix(origin: V3) = floatArrayOf(
        x.x, x.y, x.z, 0f,
        y.x, y.y, y.z, 0f,
        z.x, z.y, z.z, 0f,
        origin.x, origin.y, origin.z, 1f)
    fun quaternion(): Q = Q.fromBasis(x, y, z)

    companion object {
        val IDENTITY = Basis(V3(1f, 0f, 0f), V3(0f, 1f, 0f), V3(0f, 0f, 1f))
        /** Right-handed basis whose Y follows [y] and whose Z is as close as possible to [zHint]. */
        fun fromYZ(y: V3, zHint: V3): Basis {
            val yy = y.unit()
            var zz = zHint - yy * zHint.dot(yy)
            if (zz.length() < 1e-4f) {
                val alt = if (abs(yy.x) < .9f) V3(1f, 0f, 0f) else V3(0f, 0f, 1f)
                zz = alt - yy * alt.dot(yy)
            }
            zz = zz.unit()
            return Basis(yy.cross(zz).unit(), yy, zz)
        }
    }
}

data class Q(val x: Float, val y: Float, val z: Float, val w: Float) {
    fun slerp(other: Q, t: Float): Q {
        var b = other
        var dot = x * b.x + y * b.y + z * b.z + w * b.w
        if (dot < 0) { b = Q(-b.x, -b.y, -b.z, -b.w); dot = -dot }
        val aWeight: Float; val bWeight: Float
        if (dot > .9995f) { aWeight = 1 - t; bWeight = t }
        else {
            val theta = acos(dot.coerceIn(-1f, 1f)); val d = sin(theta)
            aWeight = sin((1 - t) * theta) / d; bWeight = sin(t * theta) / d
        }
        return Q(x * aWeight + b.x * bWeight, y * aWeight + b.y * bWeight, z * aWeight + b.z * bWeight, w * aWeight + b.w * bWeight).normalized()
    }
    fun normalized(): Q {
        val n = sqrt(x * x + y * y + z * z + w * w).coerceAtLeast(1e-6f)
        return Q(x / n, y / n, z / n, w / n)
    }
    fun basis(): Basis = Basis(
        V3(1 - 2 * y * y - 2 * z * z, 2 * x * y + 2 * w * z, 2 * x * z - 2 * w * y),
        V3(2 * x * y - 2 * w * z, 1 - 2 * x * x - 2 * z * z, 2 * y * z + 2 * w * x),
        V3(2 * x * z + 2 * w * y, 2 * y * z - 2 * w * x, 1 - 2 * x * x - 2 * y * y))
    fun matrix(position: V3, scale: V3): FloatArray {
        val b = basis()
        return floatArrayOf(
            b.x.x * scale.x, b.x.y * scale.x, b.x.z * scale.x, 0f,
            b.y.x * scale.y, b.y.y * scale.y, b.y.z * scale.y, 0f,
            b.z.x * scale.z, b.z.y * scale.z, b.z.z * scale.z, 0f,
            position.x, position.y, position.z, 1f)
    }

    companion object {
        val IDENTITY = Q(0f, 0f, 0f, 1f)
        fun fromBasis(x: V3, y: V3, z: V3): Q {
            val m00 = x.x; val m10 = x.y; val m20 = x.z
            val m01 = y.x; val m11 = y.y; val m21 = y.z
            val m02 = z.x; val m12 = z.y; val m22 = z.z
            val trace = m00 + m11 + m22
            return when {
                trace > 0 -> { val s = sqrt(trace + 1f) * 2; Q((m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, .25f * s) }
                m00 > m11 && m00 > m22 -> { val s = sqrt(1f + m00 - m11 - m22) * 2; Q(.25f * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s) }
                m11 > m22 -> { val s = sqrt(1f + m11 - m00 - m22) * 2; Q((m01 + m10) / s, .25f * s, (m12 + m21) / s, (m02 - m20) / s) }
                else -> { val s = sqrt(1f + m22 - m00 - m11) * 2; Q((m02 + m20) / s, (m12 + m21) / s, .25f * s, (m10 - m01) / s) }
            }.normalized()
        }
    }
}

data class Landmark(val p: V3, val confidence: Float)
data class PoseFrame(val id: Long, val capturedMs: Long, val inferredMs: Long,
    val image: List<Landmark>, val world: List<Landmark>, val width: Int, val height: Int,
    val hasWorldLandmarks: Boolean = true, val scoreFloor: Float = .5f,
    val observedJoints: List<Boolean>? = null)

object Coordinates {
    // MediaPipe camera coordinates: x right, y down, z away. Camera space: y up, z toward the camera.
    fun world(p: V3) = V3(p.x, -p.y, -p.z)
    fun overlay(p: V3, w: Float, h: Float, iw: Int, ih: Int, mirror: Boolean): Pair<Float, Float> {
        val scale = min(w / iw, h / ih) // PreviewView FIT_CENTER; entire oriented input remains visible.
        return Pair((w - iw * scale) / 2 + (if (mirror) 1 - p.x else p.x) * iw * scale,
            (h - ih * scale) / 2 + p.y * ih * scale)
    }
    /** Same mapping for PreviewView FILL_CENTER (cropped to fill). */
    fun overlayFill(p: V3, w: Float, h: Float, iw: Int, ih: Int, mirror: Boolean): Pair<Float, Float> {
        val scale = max(w / iw, h / ih)
        return Pair((w - iw * scale) / 2 + (if (mirror) 1 - p.x else p.x) * iw * scale,
            (h - ih * scale) / 2 + p.y * ih * scale)
    }
}
