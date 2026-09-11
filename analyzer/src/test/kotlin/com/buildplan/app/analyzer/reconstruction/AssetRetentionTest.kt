package com.buildplan.app.analyzer.reconstruction

import com.buildplan.app.analyzer.asset.*
import com.buildplan.app.analyzer.raster.*
import com.buildplan.app.analyzer.source.*
import org.junit.Assert.*
import org.junit.Test

class AssetRetentionTest {
    @Test fun `fetcher retains compressed bytes and redecodes visuals sequentially`() {
        var decodes=0
        val codec=object:RasterCodec { override fun decode(bytes:ByteArray):RasterImage { decodes++;return RasterImage(2,2,IntArray(4)) } }
        val fetcher=object:ResourceFetcher { override fun fetch(url:String)=FetchedResource(url,url,emptyList(),200,"image/test",byteArrayOf(1,2),0L) }
        val records=listOf(AssetRole.ELEVATION_FRONT,AssetRole.ELEVATION_REAR).mapIndexed { index,role -> AssetRecord(role,"https://example.test/$index.png","https://example.test/project","fixture",null) }
        val storage=AnalysisStorage { path,_->path }
        val assets=AssetFetcher(fetcher,codec,storage).fetchAll(AssetManifest(records),"test")
        assertEquals(2,decodes)
        assertTrue(assets.images.isEmpty())
        assertNotNull(assets.image(records[0]));assertNotNull(assets.image(records[1]))
        assertEquals(4,decodes)
        assertTrue(assets.images.isEmpty())
        val bounded=AssetFetcher(fetcher,codec,storage,AssetPolicy(maxTotalEncodedBytes=3)).fetchAll(AssetManifest(records),"test")
        assertEquals(RetrievalState.DECODED,bounded.manifest.assets[0].retrieval)
        assertEquals(RetrievalState.FAILED,bounded.manifest.assets[1].retrieval)
        assertNull(bounded.image(records[1]))
    }
}
