package com.velotrack.velotrack.recording

import com.velotrack.velotrack.GpsPoint
import java.util.RandomAccess

/**
 * 面向长轨迹追加优化的不可变列表。
 *
 * 常规追加只复制最多 [CHUNK_SIZE] 个尾部引用；尾块写满后才扩展块索引，避免每帧复制完整轨迹。
 */
internal class PersistentTrackPoints private constructor(
    private val completedChunks: List<List<GpsPoint>>,
    private val tail: List<GpsPoint>,
    override val size: Int,
) : AbstractList<GpsPoint>(), RandomAccess {

    override fun get(index: Int): GpsPoint {
        if (index !in 0 until size) throw IndexOutOfBoundsException("index=$index size=$size")
        val completedSize = completedChunks.size * CHUNK_SIZE
        return if (index < completedSize) {
            completedChunks[index / CHUNK_SIZE][index % CHUNK_SIZE]
        } else {
            tail[index - completedSize]
        }
    }

    fun append(point: GpsPoint): PersistentTrackPoints =
        if (tail.size < CHUNK_SIZE) {
            PersistentTrackPoints(completedChunks, tail + point, size + 1)
        } else {
            PersistentTrackPoints(completedChunks + listOf(tail), listOf(point), size + 1)
        }

    fun appendAll(points: List<GpsPoint>): PersistentTrackPoints {
        var result = this
        points.forEach { result = result.append(it) }
        return result
    }

    fun replaceLast(point: GpsPoint): PersistentTrackPoints {
        require(size > 0) { "Cannot replace the last point of an empty track" }
        val nextTail = tail.toMutableList().apply { this[lastIndex] = point }
        return PersistentTrackPoints(completedChunks, nextTail, size)
    }

    companion object {
        private const val CHUNK_SIZE = 256
        private val EMPTY = PersistentTrackPoints(emptyList(), emptyList(), 0)

        fun from(points: List<GpsPoint>): PersistentTrackPoints {
            if (points is PersistentTrackPoints) return points
            if (points.isEmpty()) return EMPTY
            val chunks = points.chunked(CHUNK_SIZE)
            return PersistentTrackPoints(
                completedChunks = chunks.dropLast(1),
                tail = chunks.last(),
                size = points.size,
            )
        }
    }
}

/** 无复制地把候选点暴露为列表尾元素，仅供当前帧估速。 */
internal class AppendedPointView(
    private val base: List<GpsPoint>,
    private val last: GpsPoint,
) : AbstractList<GpsPoint>(), RandomAccess {
    override val size: Int get() = base.size + 1
    override fun get(index: Int): GpsPoint = when {
        index < 0 || index >= size -> throw IndexOutOfBoundsException("index=$index size=$size")
        index == base.size -> last
        else -> base[index]
    }
}

internal fun List<GpsPoint>.appendTrackPoint(point: GpsPoint): PersistentTrackPoints =
    PersistentTrackPoints.from(this).append(point)

internal fun List<GpsPoint>.replaceLastTrackPoint(point: GpsPoint): PersistentTrackPoints =
    PersistentTrackPoints.from(this).replaceLast(point)

internal fun List<GpsPoint>.asPersistentTrackPoints(): PersistentTrackPoints =
    PersistentTrackPoints.from(this)
