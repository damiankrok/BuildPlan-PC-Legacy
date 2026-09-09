package com.buildplan.app.analyzer.service

import com.buildplan.app.analyzer.ArchonFixture
import com.buildplan.app.analyzer.FakeFetcher
import com.buildplan.app.analyzer.evaluation.ImageIoRasterCodec
import com.buildplan.app.analyzer.raster.RasterCodec
import com.buildplan.app.analyzer.raster.RasterImage
import com.buildplan.app.analyzer.source.ResourceFetcher
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import javax.imageio.ImageIO

/**
 * A whole supported site, served from memory: the project page, its cost page
 * and every drawing it names, with the drawings being a plan of known
 * geometry rather than a picture of one.
 *
 * Nothing here is copied from the live site — the markup skeleton is
 * [ArchonFixture]'s invented project, and the raster is drawn by this file.
 * That is the point: the service's behaviour has to be provable without a
 * socket, and a test that needs the network is a test that is skipped.
 */
object ServiceFixture {

    const val PPM = 40.0
    private const val W = 560
    private const val H = 420

    /** Every drawing URL the fixture page refers to, in the roles the adapter assigns them. */
    val assetUrls: List<String> = listOf(
        "${ArchonFixture.ASSETS}/widok-1-projekt-dom-testowy-x-aaa__289.jpg",
        "${ArchonFixture.ASSETS}/rzut-parteru-z-powierzchniami-projekt-dom-testowy-x-bbb__11915.gif",
        "${ArchonFixture.ASSETS}/projekt-dom-testowy-x-ccc__11815.gif",
        "${ArchonFixture.ASSETS}/rzut-poddasza-z-powierzchniami-projekt-dom-testowy-x-ddd__11917.gif",
        "${ArchonFixture.ASSETS}/projekt-dom-testowy-x-eee__11817.gif",
        "${ArchonFixture.ASSETS}/przekroj-budynku-projekt-dom-testowy-x-fff__256.jpg",
        "${ArchonFixture.ASSETS}/sytuacja-projekt-dom-testowy-x-ggg__255.jpg",
        "${ArchonFixture.ASSETS}/elewacja-frontowa-projekt-dom-testowy-x-hhh__264.jpg",
        "${ArchonFixture.ASSETS}/elewacja-boczna-projekt-dom-testowy-x-iii__265.jpg",
        "${ArchonFixture.ASSETS}/elewacja-boczna-projekt-dom-testowy-x-jjj__266.jpg",
        "${ArchonFixture.ASSETS}/elewacja-ogrodowa-projekt-dom-testowy-x-kkk__267.jpg",
    )

    /**
     * The fixture's fetcher.
     *
     * @param page the project page HTML; overridable so drift tests can serve a
     *   page whose markup has moved on.
     * @param plans when false the drawings are served as bytes that are not an
     *   image, which is how "the page is fine and the drawings are not" is
     *   tested without inventing a network failure.
     */
    fun fetcher(
        page: String = ArchonFixture.page,
        costPage: String? = ArchonFixture.costPage,
        plans: Boolean = true,
        serveAssets: Boolean = true,
    ): FakeFetcher = FakeFetcher().apply {
        serve(ArchonFixture.PAGE_URL, page)
        if (costPage != null) serve(ArchonFixture.COST_URL, costPage)
        if (serveAssets) {
            val bytes = if (plans) planPng() else "not an image".toByteArray()
            assetUrls.forEach { serveBytes(it, bytes, "image/png") }
        }
    }

    fun platform(fetcher: ResourceFetcher, codec: RasterCodec = ImageIoRasterCodec): AnalyzerPlatform =
        object : AnalyzerPlatform {
            override val codec: RasterCodec = codec
            override fun networkFetcher(): ResourceFetcher = fetcher

            /**
             * A fixed public address for every host.
             *
             * The private-address rule has its own tests with its own
             * resolver; here it must not turn into a DNS lookup, because a
             * test suite that resolves real names fails on an aeroplane.
             */
            override fun resolve(host: String): List<InetAddress> =
                listOf(InetAddress.getByAddress(host, byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34)))
        }

    /**
     * A plan of a 10 x 8 m house at 40 px/m: 0.44 m exterior walls, one
     * 0.12 m partition, a door in it, a window and an entrance.
     *
     * The same drawing as `SyntheticPlanTest` uses, because the point of both
     * is that the expected numbers are the drawing's own and no project is
     * involved.
     */
    fun planImage(): RasterImage {
        val argb = IntArray(W * H) { 0xFFFFFFFF.toInt() }
        fun px(m: Double) = (m * PPM).toInt()
        fun fill(x0: Int, y0: Int, x1: Int, y1: Int, colour: Int) {
            for (y in y0 until y1) for (x in x0 until x1) if (x in 0 until W && y in 0 until H) argb[y * W + x] = colour
        }
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        val floor = 0xFFEBECEC.toInt()
        val ox = 60
        val oy = 40
        val t = px(0.44)
        val p = px(0.12)
        fill(ox, oy, ox + px(10.0), oy + px(8.0), floor)
        fill(ox, oy, ox + px(10.0), oy + t, black)
        fill(ox, oy + px(8.0) - t, ox + px(10.0), oy + px(8.0), black)
        fill(ox, oy, ox + t, oy + px(8.0), black)
        fill(ox + px(10.0) - t, oy, ox + px(10.0), oy + px(8.0), black)
        val partition = ox + px(6.44)
        fill(partition, oy + t, partition + p, oy + px(8.0) - t, black)
        fill(partition, oy + px(3.0), partition + p, oy + px(3.9), floor)
        fill(ox + px(2.0), oy, ox + px(3.5), oy + t, white)
        fill(ox + px(2.0), oy + t / 2, ox + px(3.5), oy + t / 2 + 1, black)
        fill(ox + px(8.0), oy + px(8.0) - t, ox + px(9.0), oy + px(8.0), white)
        return RasterImage(W, H, argb)
    }

    fun planPng(): ByteArray {
        val image = planImage()
        val buffered = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        buffered.setRGB(0, 0, image.width, image.height, image.argb, 0, image.width)
        val out = ByteArrayOutputStream()
        ImageIO.write(buffered, "png", out)
        return out.toByteArray()
    }
}
