package com.alexvas.rtsp.codec

import android.media.MediaCodec.OnFrameRenderedListener
import android.util.Log
import android.view.Surface
import com.google.android.exoplayer2.util.Util
import java.util.concurrent.atomic.AtomicBoolean
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import android.annotation.SuppressLint
import android.content.ContentUris
import android.database.Cursor
import android.graphics.*
import android.media.*
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.widget.Toast
import com.alexvas.rtsp.R
import com.alexvas.utils.ImageUtils
import com.alexvas.utils.MyFileWriter
import java.io.File
import java.nio.ByteBuffer


class VideoDecodeThread(
    private val surface: Surface?,
    private val mimeType: String,
    private val width: Int,
    private val height: Int,
    private val videoFrameQueue: FrameQueue,
    private val onFrameRenderedListener: OnFrameRenderedListener,
    private val context: Context
) : Thread() {

    private var exitFlag: AtomicBoolean = AtomicBoolean(false)
    private val FILES_DIR: String? = Environment.getExternalStorageDirectory().absolutePath+"/Documents/"
    private val INPUT_FILE = "source.mp4"

    fun stopAsync() {
        if (DEBUG) Log.v(TAG, "stopAsync()")
        exitFlag.set(true)
        // Wake up sleep() code
        interrupt()
    }

    private fun getDecoderSafeWidthHeight(decoder: MediaCodec): Pair<Int, Int> {
        val capabilities = decoder.codecInfo.getCapabilitiesForType(mimeType).videoCapabilities
        return if (capabilities.isSizeSupported(width, height)) {
            Pair(width, height)
        } else {
            val widthAlignment = capabilities.widthAlignment
            val heightAlignment = capabilities.heightAlignment
            Pair(
                Util.ceilDivide(width, widthAlignment) * widthAlignment,
                Util.ceilDivide(height, heightAlignment) * heightAlignment)
        }
    }

    private var mPreviewWidth = 0
    private var mPreviewHeight = 0
    private var count = 0
    private lateinit var mYUVBytes: Array<ByteArray?>
    private var mCroppedBitmap: Bitmap? = null
    private var mRGBFrameBitmap: Bitmap? = null
    private var mRGBBytes: IntArray? = null

    private val file by lazy {
        context.resources.openRawResourceFd(R.raw.sample_video)
    }

    private lateinit var extractor: MediaExtractor
    private lateinit var decoder: MediaCodec
    var image: Image? = null

    private var isStop = false
    @RequiresApi(Build.VERSION_CODES.O)
    override fun run() {

        isStop = false
        try {
            val inputFile : String = getFilePath()
            extractor = MediaExtractor()
            extractor.setDataSource(inputFile)

//            (0..extractor.trackCount).forEach { index ->
             val index = selectTrack(extractor)
                val format = extractor.getTrackFormat(index)

                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith(VIDEO) == true) {
                    extractor.selectTrack(index)
                    decoder = MediaCodec.createDecoderByType(mime)
                    try {
                        Log.d(TAG, "format : $format")
                        decoder.configure(format, surface, null, 0 /* Decode */)
                    } catch (e: IllegalStateException) {
                        Log.e(TAG, "codec $mime failed configuration. $e")
                    }
                    decoder.start()
                }
//            }
        } catch (e: Exception) {
            e.printStackTrace()
        }


        val newBufferInfo = MediaCodec.BufferInfo()
        val inputBuffers: Array<ByteBuffer> = decoder.inputBuffers
        decoder.outputBuffers

        var isInput = true
        var isFirst = false
        var startWhen = 0L

        while (isStop.not()) {
            decoder.dequeueInputBuffer(1000).takeIf { it >= 0 }?.let { index ->
                // fill inputBuffers[inputBufferIndex] with valid data
                val inputBuffer = inputBuffers[index]

                val sampleSize = extractor.readSampleData(inputBuffer, 0)

                if (extractor.advance() && sampleSize > 0) {
                    decoder.queueInputBuffer(index, 0, sampleSize, extractor.sampleTime, 0)
                } else {
                    Log.d(TAG, "InputBuffer BUFFER_FLAG_END_OF_STREAM")
                    decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    isInput = false
                }
            }

            val outIndex = decoder.dequeueOutputBuffer(newBufferInfo, 1000)
            when (outIndex) {
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                    Log.d(TAG, "INFO_OUTPUT_BUFFERS_CHANGED")
                    decoder.outputBuffers
                }
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.d(TAG, "INFO_OUTPUT_FORMAT_CHANGED format : " + decoder.outputFormat)
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    Log.d(TAG, "INFO_TRY_AGAIN_LATER")
                }
                else -> {
                    if (isFirst.not()) {
                        startWhen = System.currentTimeMillis()
                        isFirst = true
                    }
                    try {
                        val sleepTime: Long =
                            newBufferInfo.presentationTimeUs / 1000 - (System.currentTimeMillis() - startWhen)
                        Log.d(
                            TAG,
                            "info.presentationTimeUs : " + (newBufferInfo.presentationTimeUs / 1000).toString() + " playTime: " + (System.currentTimeMillis() - startWhen).toString() + " sleepTime : " + sleepTime
                        )
                        if (sleepTime > 0) sleep(sleepTime)

                    } catch (e: InterruptedException) {
                        // TODO Auto-generated catch block
                        e.printStackTrace()
                    }

                    if (outIndex>=0) {
                        image = decoder.getOutputImage(outIndex)
                        val planes = image?.planes

                        if (image != null) {
                            if (mPreviewWidth != image!!.width || mPreviewHeight != image!!.height) {
                                mPreviewWidth = image!!.width
                                mPreviewHeight = image!!.height
                                Log.d(
                                    TAG,
                                    String.format(
                                        "Initializing at size %dx%d",
                                        mPreviewWidth,
                                        mPreviewHeight
                                    )
                                )
                                mRGBBytes = IntArray(mPreviewWidth * mPreviewHeight)
                                mRGBFrameBitmap =
                                    Bitmap.createBitmap(
                                        mPreviewWidth,
                                        mPreviewHeight,
                                        Bitmap.Config.ARGB_8888
                                    )
//                                    mCroppedBitmap = Bitmap.createBitmap(
//                                        mPreviewWidth,
//                                        mPreviewHeight,
//                                        Bitmap.Config.ARGB_8888
//                                    )
                                mYUVBytes = arrayOfNulls<ByteArray>(planes!!.size)
                                for (i in planes.indices) {
                                    mYUVBytes[i] = ByteArray(planes[i].buffer.capacity())
                                }
                            }

                            for (i in planes!!.indices) {
                                planes[i].buffer.get(mYUVBytes[i] as ByteArray)
                            }
                            val yRowStride = planes[0].rowStride
                            val uvRowStride = planes[1].rowStride
                            val uvPixelStride = planes[1].pixelStride
                            ImageUtils.convertYUV420ToARGB8888(
                                mYUVBytes[0],
                                mYUVBytes[1],
                                mYUVBytes[2],
                                mPreviewWidth,
                                mPreviewHeight,
                                yRowStride,
                                uvRowStride,
                                uvPixelStride,
                                mRGBBytes
                            )
                            image!!.close()
                        }

                        mRGBFrameBitmap?.setPixels(
                            mRGBBytes,
                            0,
                            mPreviewWidth,
                            0,
                            0,
                            mPreviewWidth,
                            mPreviewHeight
                        )
                        mCroppedBitmap = mRGBFrameBitmap
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            if (mCroppedBitmap != null) {
                                MyFileWriter.saveBitmap(
                                    mCroppedBitmap,
                                    (count + 1).toString(),
                                    context
                                )
                            }
                        }
                        count++

//                        decoder.releaseOutputBuffer(outIndex, true /* Surface init */)
                    }
                    decoder.releaseOutputBuffer(outIndex, true /* Surface init */)
                }
            }

            // All decoded frames have been rendered, we can stop playing now
            // All decoded frames have been rendered, we can stop playing now
            if (newBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                Log.d(TAG, "OutputBuffer BUFFER_FLAG_END_OF_STREAM")
                break
            }
        }

        decoder.stop()
        decoder.release()
        extractor.release()

        if (DEBUG) Log.d(TAG, "$name stopped")
    }

    private fun selectTrack(extractor: MediaExtractor): Int {
        // Select the first video track we find, ignore the rest.
        val numTracks = extractor.trackCount
        for (i in 0 until numTracks) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime!!.startsWith("video/")) {
//                if (VERBOSE) {
                    Log.d(TAG, "Extractor selected track $i ($mime): $format")
//                }
                return i
            }
        }
        return -1
    }

    @SuppressLint("Range")
    private fun getFilePath(): String {
        var vieoPath: String? = null
        val contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val selection = MediaStore.MediaColumns.RELATIVE_PATH + "=?"
        val selectionArgs =
            arrayOf(Environment.DIRECTORY_DOCUMENTS.toString() + "/Video/")
        val cursor: Cursor? =
            context.contentResolver.query(contentUri, null, selection, selectionArgs, null)
        var uri: Uri? = null
        if (cursor!= null){
            if (cursor.count ==0){
                Toast.makeText(
                    context,
                    "No file found in \"" + Environment.DIRECTORY_DOCUMENTS + "/Video/\"",
                    Toast.LENGTH_LONG
                ).show();
            } else{
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(cursor.getColumnIndex(MediaStore.MediaColumns._ID))
                    uri = ContentUris.withAppendedId(contentUri, id);
                    vieoPath = getFullPathFromContentUri(context, uri)
                }
            }
        }
        return vieoPath!!
    }

    private fun getFullPathFromContentUri(context: Context, uri: Uri): String? {
        var filePath: String? = null
        val isKitKat = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT

        // DocumentProvider

        // DocumentProvider
        if (isKitKat && DocumentsContract.isDocumentUri(context, uri)) {
            // ExternalStorageProvider
            if ("com.android.externalstorage.documents" == uri.authority) {
                val docId = DocumentsContract.getDocumentId(uri)
                val split = docId.split(":".toRegex()).toTypedArray()
                val type = split[0]
                return if ("primary".equals(type, ignoreCase = true)) {
                    Environment.getExternalStorageDirectory().toString() + "/" + split[1]
                } //non-primary e.g sd card
                else {
                    if (Build.VERSION.SDK_INT > 20) {
                        //getExternalMediaDirs() added in API 21
                        val extenal = context.externalMediaDirs
                        for (f in extenal) {
                            filePath = f.absolutePath
                            if (filePath.contains(type)) {
                                val endIndex = filePath.indexOf("Android")
                                filePath = filePath.substring(0, endIndex) + split[1]
                            }
                        }
                    } else {
                        filePath = "/storage/" + type + "/" + split[1]
                    }
                    filePath
                }
            } else if ("com.android.providers.downloads.documents" == uri.authority) {
                val id = DocumentsContract.getDocumentId(uri)
                val contentUri = ContentUris.withAppendedId(
                    Uri.parse("content://downloads/public_downloads"), java.lang.Long.valueOf(id)
                )
                return getDataColumn(
                    context,
                    contentUri,
                    null,
                    null
                )
            } else if ("com.android.providers.media.documents" == uri.authority) {
                val docId = DocumentsContract.getDocumentId(uri)
                val split = docId.split(":".toRegex()).toTypedArray()
                val type = split[0]
                var contentUri: Uri? = null
                if ("image" == type) {
                    contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                } else if ("video" == type) {
                    contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                } else if ("audio" == type) {
                    contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                }
                val selection = "_id=?"
                val selectionArgs = arrayOf(
                    split[1]
                )
                var cursor: Cursor? = null
                val column = "_data"
                val projection = arrayOf(
                    column
                )
                try {
                    cursor = context.contentResolver.query(
                        uri, projection, selection, selectionArgs,
                        null
                    )
                    if (cursor != null && cursor.moveToFirst()) {
                        val column_index = cursor.getColumnIndexOrThrow(column)
                        return cursor.getString(column_index)
                    }
                } finally {
                    cursor?.close()
                }
                return null
            }
        } else if ("content".equals(uri.scheme, ignoreCase = true)) {
            return getDataColumn(
                context,
                uri,
                null,
                null
            )
        } else if ("file".equals(uri.scheme, ignoreCase = true)) {
            return uri.path
        }

        return null
    }

    private fun getDataColumn(
        context: Context, uri: Uri, selection: String?,
        selectionArgs: Array<String>?
    ): String? {
        var cursor: Cursor? = null
        val column = "_data"
        val projection = arrayOf(
            column
        )
        try {
            cursor = context.contentResolver.query(
                uri, projection, selection, selectionArgs,
                null
            )
            if (cursor != null && cursor.moveToFirst()) {
                val column_index = cursor.getColumnIndexOrThrow(column)
                return cursor.getString(column_index)
            }
        } finally {
            cursor?.close()
        }
        return null
    }

    companion object {
        private val TAG: String = VideoDecodeThread::class.java.simpleName
        private const val DEBUG = false
        private const val VIDEO = "video/"
    }

}

