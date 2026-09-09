package com.buildplan.app.analyzer.raster

/**
 * A boolean image with the handful of morphological and topological
 * operations the plan analysis needs, written directly on arrays.
 *
 * No OpenCV. The plans the analyzer reads are clean vector renders with
 * solid axis-aligned walls, and what such a raster needs is thresholding,
 * rectangular erosion/dilation, connected components and run-length
 * scans — a few hundred lines that run the same on a phone and on the JVM,
 * against a native library that would need its own ABI and 16 KB audit.
 */
class BinaryMask(val width: Int, val height: Int, val bits: BooleanArray = BooleanArray(width * height)) {
    init {
        require(bits.size == width * height) { "mask size mismatch" }
    }

    operator fun get(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height && bits[y * width + x]
    operator fun set(x: Int, y: Int, v: Boolean) {
        bits[y * width + x] = v
    }

    fun count(): Int = bits.count { it }

    fun copy(): BinaryMask = BinaryMask(width, height, bits.copyOf())

    fun or(other: BinaryMask): BinaryMask = BinaryMask(width, height, BooleanArray(bits.size) { bits[it] || other.bits[it] })
    fun and(other: BinaryMask): BinaryMask = BinaryMask(width, height, BooleanArray(bits.size) { bits[it] && other.bits[it] })
    fun andNot(other: BinaryMask): BinaryMask = BinaryMask(width, height, BooleanArray(bits.size) { bits[it] && !other.bits[it] })
    fun not(): BinaryMask = BinaryMask(width, height, BooleanArray(bits.size) { !bits[it] })

    /** Erosion by a (2*rx+1) x (2*ry+1) rectangle: a pixel survives only if its whole neighbourhood is set. */
    fun erode(rx: Int, ry: Int): BinaryMask {
        val h = BinaryMask(width, height)
        // Horizontal pass: run lengths.
        for (y in 0 until height) {
            var run = 0
            for (x in 0 until width) {
                run = if (bits[y * width + x]) run + 1 else 0
                if (run >= 2 * rx + 1) h[x - rx, y] = true
            }
        }
        if (ry == 0) return h
        val v = BinaryMask(width, height)
        for (x in 0 until width) {
            var run = 0
            for (y in 0 until height) {
                run = if (h.bits[y * width + x]) run + 1 else 0
                if (run >= 2 * ry + 1) v[x, y - ry] = true
            }
        }
        return v
    }

    /** Dilation by a (2*rx+1) x (2*ry+1) rectangle. */
    fun dilate(rx: Int, ry: Int): BinaryMask = not().erode(rx, ry).not().also { it.clearBorderArtifacts(rx, ry, this) }

    /** Opening: erosion then dilation; removes features thinner than the kernel. */
    fun open(rx: Int, ry: Int): BinaryMask = erode(rx, ry).dilate(rx, ry)

    /**
     * Erosion by a `wx` x `wy` window of any size, anchored at the window's
     * first pixel: a pixel survives when the window starting at it is all set.
     * Even sizes matter here — a 4 px window is what separates a 4 px partition
     * from a 3 px anti-aliased line, and odd kernels cannot express it.
     */
    fun erodeWindow(wx: Int, wy: Int): BinaryMask {
        val h = BinaryMask(width, height)
        for (y in 0 until height) {
            var run = 0
            for (x in 0 until width) {
                run = if (bits[y * width + x]) run + 1 else 0
                if (run >= wx) h[x - wx + 1, y] = true
            }
        }
        if (wy <= 1) return h
        val v = BinaryMask(width, height)
        for (x in 0 until width) {
            var run = 0
            for (y in 0 until height) {
                run = if (h.bits[y * width + x]) run + 1 else 0
                if (run >= wy) v[x, y - wy + 1] = true
            }
        }
        return v
    }

    /** Dilation by a `wx` x `wy` window, the reflection of [erodeWindow], so that the pair is an opening. */
    fun dilateWindow(wx: Int, wy: Int): BinaryMask {
        val out = BinaryMask(width, height)
        for (y in 0 until height) for (x in 0 until width) {
            if (!bits[y * width + x]) continue
            for (dy in 0 until wy) {
                val yy = y + dy
                if (yy >= height) break
                for (dx in 0 until wx) {
                    val xx = x + dx
                    if (xx >= width) break
                    out.bits[yy * width + xx] = true
                }
            }
        }
        return out
    }

    /** Opening with an arbitrary window: keeps only features at least `wx` wide and `wy` tall. */
    fun openWindow(wx: Int, wy: Int): BinaryMask = erodeWindow(wx, wy).dilateWindow(wx, wy)

    /** Closing: dilation then erosion; fills gaps narrower than the kernel. */
    fun close(rx: Int, ry: Int): BinaryMask = dilate(rx, ry).erode(rx, ry)

    /**
     * Dilation through complement erosion treats the outside of the image as
     * "set" in the complement, which would grow set pixels along the border;
     * this restores the true dilation there by recomputing border bands.
     */
    private fun clearBorderArtifacts(rx: Int, ry: Int, source: BinaryMask) {
        val bandX = minOf(rx, width)
        val bandY = minOf(ry, height)
        fun recompute(x: Int, y: Int) {
            var any = false
            var dy = -ry
            while (!any && dy <= ry) {
                var dx = -rx
                while (!any && dx <= rx) {
                    if (source[x + dx, y + dy]) any = true
                    dx++
                }
                dy++
            }
            this[x, y] = any
        }
        for (y in 0 until height) {
            for (x in 0 until bandX) recompute(x, y)
            for (x in (width - bandX).coerceAtLeast(0) until width) recompute(x, y)
        }
        for (x in 0 until width) {
            for (y in 0 until bandY) recompute(x, y)
            for (y in (height - bandY).coerceAtLeast(0) until height) recompute(x, y)
        }
    }

    /**
     * 4-connected components. Returns labels (0 = unset) and the component
     * sizes indexed by label.
     */
    fun components(): Components {
        val labels = IntArray(width * height)
        val sizes = mutableListOf(0)
        val stack = IntArray(width * height)
        var next = 1
        for (start in bits.indices) {
            if (!bits[start] || labels[start] != 0) continue
            var sp = 0
            stack[sp++] = start
            labels[start] = next
            var size = 0
            while (sp > 0) {
                val idx = stack[--sp]
                size++
                val x = idx % width
                val y = idx / width
                if (x > 0 && bits[idx - 1] && labels[idx - 1] == 0) { labels[idx - 1] = next; stack[sp++] = idx - 1 }
                if (x < width - 1 && bits[idx + 1] && labels[idx + 1] == 0) { labels[idx + 1] = next; stack[sp++] = idx + 1 }
                if (y > 0 && bits[idx - width] && labels[idx - width] == 0) { labels[idx - width] = next; stack[sp++] = idx - width }
                if (y < height - 1 && bits[idx + width] && labels[idx + width] == 0) { labels[idx + width] = next; stack[sp++] = idx + width }
            }
            sizes += size
            next++
        }
        return Components(width, height, labels, sizes)
    }

    /** Keeps only components of at least [minPixels]. */
    fun withoutSmallComponents(minPixels: Int): BinaryMask {
        val c = components()
        val out = BinaryMask(width, height)
        for (i in bits.indices) if (bits[i] && c.sizes[c.labels[i]] >= minPixels) out.bits[i] = true
        return out
    }

    /** Bounding box of the set pixels, or null when empty. */
    fun boundingBox(): PixelBox? {
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        for (y in 0 until height) for (x in 0 until width) if (bits[y * width + x]) {
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        return if (maxX < 0) null else PixelBox(minX, minY, maxX, maxY)
    }

    /**
     * The mask of pixels reachable from the image border — and from any set pixel of
     * [seed] — through unset pixels of this mask: the "outside".
     */
    fun outsideRegion(seed: BinaryMask? = null): BinaryMask {
        val out = BinaryMask(width, height)
        val stack = IntArray(width * height)
        var sp = 0
        fun push(idx: Int) {
            if (!bits[idx] && !out.bits[idx]) {
                out.bits[idx] = true
                stack[sp++] = idx
            }
        }
        for (x in 0 until width) { push(x); push((height - 1) * width + x) }
        for (y in 0 until height) { push(y * width); push(y * width + width - 1) }
        seed?.bits?.forEachIndexed { idx, set -> if (set) push(idx) }
        while (sp > 0) {
            val idx = stack[--sp]
            val x = idx % width
            val y = idx / width
            if (x > 0) push(idx - 1)
            if (x < width - 1) push(idx + 1)
            if (y > 0) push(idx - width)
            if (y < height - 1) push(idx + width)
        }
        return out
    }

    /** Horizontal runs of set pixels on row [y]: pairs of (startX, endXExclusive). */
    fun rowRuns(y: Int): List<IntRange> {
        val runs = mutableListOf<IntRange>()
        var start = -1
        for (x in 0..width) {
            val set = x < width && bits[y * width + x]
            if (set && start < 0) start = x
            if (!set && start >= 0) { runs += start until x; start = -1 }
        }
        return runs
    }

    fun columnRuns(x: Int): List<IntRange> {
        val runs = mutableListOf<IntRange>()
        var start = -1
        for (y in 0..height) {
            val set = y < height && bits[y * width + x]
            if (set && start < 0) start = y
            if (!set && start >= 0) { runs += start until y; start = -1 }
        }
        return runs
    }

    companion object {
        /** Pixels whose predicate holds. */
        fun of(image: RasterImage, predicate: (x: Int, y: Int) -> Boolean): BinaryMask {
            val mask = BinaryMask(image.width, image.height)
            for (y in 0 until image.height) for (x in 0 until image.width) if (predicate(x, y)) mask[x, y] = true
            return mask
        }
    }
}

class Components(val width: Int, val height: Int, val labels: IntArray, val sizes: List<Int>) {
    val count: Int get() = sizes.size - 1

    fun label(x: Int, y: Int): Int = if (x in 0 until width && y in 0 until height) labels[y * width + x] else 0

    fun maskOf(label: Int): BinaryMask = BinaryMask(width, height, BooleanArray(labels.size) { labels[it] == label })

    fun boundingBox(label: Int): PixelBox? {
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        for (i in labels.indices) if (labels[i] == label) {
            val x = i % width
            val y = i / width
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        return if (maxX < 0) null else PixelBox(minX, minY, maxX, maxY)
    }

    /** Labels touching the image border. */
    fun borderLabels(): Set<Int> {
        val out = mutableSetOf<Int>()
        for (x in 0 until width) { out += labels[x]; out += labels[(height - 1) * width + x] }
        for (y in 0 until height) { out += labels[y * width]; out += labels[y * width + width - 1] }
        out -= 0
        return out
    }
}

data class PixelBox(val minX: Int, val minY: Int, val maxX: Int, val maxY: Int) {
    val width: Int get() = maxX - minX + 1
    val height: Int get() = maxY - minY + 1
    val area: Int get() = width * height
}
