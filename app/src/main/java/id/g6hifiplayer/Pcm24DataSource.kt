package id.g6hifiplayer

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * Build 1.61: the player's data source. For hi-res FLAC (deeper than 16 bits) it serves the 24-bit WAV made by
 * [Pcm24Transcoder] instead of the FLAC bytes, so the player gets real 24-bit PCM and never goes through Android's
 * 16-bit FLAC decoder. Every other file is read as it is.
 *
 * open() runs on a loader thread, so it may block while FFmpeg decodes. The player prefetches the next queue item,
 * which gives the decode a head start while the current track plays.
 */
@UnstableApi
internal class Pcm24DataSource(
    private val context: Context,
    private val upstream: DataSource
) : DataSource {

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        val original = dataSpec.uri
        val served = try {
            Pcm24Transcoder.servedUri(context, original)
        } catch (e: IOException) {
            throw e
        } catch (t: Throwable) {
            throw IOException(t)
        }
        val spec = if (served == original) dataSpec else dataSpec.buildUpon().setUri(served).build()
        return upstream.open(spec)
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    @Throws(IOException::class)
    override fun close() {
        upstream.close()
    }

    class Factory(private val context: Context) : DataSource.Factory {
        private val upstreamFactory = DefaultDataSource.Factory(context)
        override fun createDataSource(): DataSource = Pcm24DataSource(context, upstreamFactory.createDataSource())
    }
}
