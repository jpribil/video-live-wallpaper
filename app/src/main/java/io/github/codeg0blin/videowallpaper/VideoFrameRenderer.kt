package io.github.codeg0blin.videowallpaper

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.util.Log
import android.view.Surface
import androidx.core.graphics.createBitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws video frames or GIF frames onto a [Surface] with OpenGL ES 2.0,
 * applying Fill/Fit placement from [CropGeometry].
 *
 * Why GL at all: a `WallpaperService` surface can't be resized, so the usual
 * "let the player scale the video" approach does nothing there (issue #1).
 * Instead the player decodes into [videoInputSurface] (backed by a
 * `SurfaceTexture`) and this class draws that texture as a cropped or
 * letterboxed quad. GIF frames arrive as ARGB pixels and are uploaded into a
 * second, ordinary 2D texture.
 *
 * The same class drives both the live wallpaper and the in-app preview (a
 * `TextureView`), which is what makes the preview match the wallpaper exactly.
 *
 * Threading: every method must be called on one thread, the one that called
 * [start]. EGL contexts are bound to a thread. [PlaybackController] owns that
 * thread.
 */
class VideoFrameRenderer {

    enum class Content { NONE, VIDEO, GIF }

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    // Video: a GL_TEXTURE_EXTERNAL_OES texture (required for decoder output)
    // wrapped in a SurfaceTexture, plus the Surface the player renders into.
    private var videoTextureId = 0
    private var videoSurfaceTexture: SurfaceTexture? = null

    /** Surface to hand to the video player. Null until [start] succeeds. */
    var videoInputSurface: Surface? = null
        private set

    // The SurfaceTexture's transform for the latest video frame. It carries
    // the video's rotation (portrait phone clips are usually stored sideways
    // with a rotation tag), a vertical flip and any codec padding crop.
    // Starts as the plain vertical flip an upright, unpadded video produces.
    // Column-major, as GL expects.
    private val texMatrix = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, -1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 1f, 0f, 1f
    )
    private var hasVideoFrame = false

    private val videoProgram = Program()
    private var texMatrixHandle = -1

    // GIF: plain GL_TEXTURE_2D plus a staging bitmap, created lazily.
    private val gifProgram = Program()
    private var gifTextureId = 0
    private var gifBitmap: Bitmap? = null
    private var gifWidth = 0
    private var gifHeight = 0

    // What to draw and where.
    private var content = Content.NONE
    private var videoWidth = 0
    private var videoHeight = 0
    private var scalingMode = ScalingMode.FILL
    private var focusX = 0.5f
    private var focusY = 0.5f
    private var geometryDirty = true
    private var lastViewportWidth = 0
    private var lastViewportHeight = 0
    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    /** EGL error code from the last failed swap, or EGL_SUCCESS. */
    var lastEglError: Int = EGL14.EGL_SUCCESS
        private set

    /**
     * Creates the EGL context and a window surface on [surface], plus the
     * video texture and shaders. Returns false (leaving nothing allocated) on
     * any failure; the caller should then not use this instance.
     */
    fun start(surface: Surface): Boolean {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            Log.e(TAG, "eglGetDisplay failed")
            return false
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            Log.e(TAG, "eglInitialize failed")
            eglDisplay = EGL14.EGL_NO_DISPLAY
            return false
        }
        val config = chooseConfig() ?: return fail("eglChooseConfig found no RGBA8888 ES2 config")

        eglContext = EGL14.eglCreateContext(
            eglDisplay, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        if (eglContext == EGL14.EGL_NO_CONTEXT) return fail("eglCreateContext failed")

        eglSurface = try {
            EGL14.eglCreateWindowSurface(eglDisplay, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        } catch (e: IllegalArgumentException) {
            // Thrown if the Surface was already released.
            EGL14.EGL_NO_SURFACE
        }
        if (eglSurface == EGL14.EGL_NO_SURFACE) return fail("eglCreateWindowSurface failed")
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            return fail("eglMakeCurrent failed")
        }
        if (!createVideoTexture()) return fail("Could not create video texture")
        if (!videoProgram.build(VIDEO_VERTEX_SHADER, VIDEO_FRAGMENT_SHADER)) return fail("Video shader failed")
        texMatrixHandle = GLES20.glGetUniformLocation(videoProgram.id, "uTexMatrix")

        Log.i(TAG, "EGL ${version[0]}.${version[1]} ready")
        return true
    }

    private fun fail(message: String): Boolean {
        Log.e(TAG, message)
        release()
        return false
    }

    private fun createVideoTexture(): Boolean {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        videoTextureId = ids[0]
        if (videoTextureId == 0) return false
        configureBoundTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId)
        val st = SurfaceTexture(videoTextureId)
        videoSurfaceTexture = st
        videoInputSurface = Surface(st)
        return true
    }

    private fun configureBoundTexture(target: Int, id: Int) {
        GLES20.glBindTexture(target, id)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        // CLAMP_TO_EDGE and no mipmaps: required for non-power-of-two
        // textures in ES 2.0, and neither videos nor GIFs are power-of-two.
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    /**
     * Calls [listener] on [handler]'s thread (which must be this renderer's
     * thread) whenever the player has produced a new video frame. The
     * listener should call [latchVideoFrame] and then usually [draw].
     */
    fun setVideoFrameListener(handler: Handler, listener: () -> Unit) {
        videoSurfaceTexture?.setOnFrameAvailableListener({ listener() }, handler)
    }

    /**
     * Takes the newest decoded video frame into the texture. Always call it
     * when a frame arrives, even if nothing will be drawn: until a frame is
     * taken, the player can run out of buffers and stall.
     */
    fun latchVideoFrame() {
        val st = videoSurfaceTexture ?: return
        try {
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
            hasVideoFrame = true
        } catch (e: RuntimeException) {
            // Thrown if the context isn't current or the producer went away.
            Log.w(TAG, "updateTexImage failed", e)
        }
    }

    /** Selects what [draw] shows. Switching to VIDEO shows black until a new frame is latched. */
    fun setContent(newContent: Content) {
        if (newContent == content) return
        if (newContent == Content.VIDEO) hasVideoFrame = false
        content = newContent
        geometryDirty = true
    }

    /** Display size of the video (after rotation and pixel aspect ratio). */
    fun setVideoSize(width: Int, height: Int) {
        if (width == videoWidth && height == videoHeight) return
        videoWidth = width
        videoHeight = height
        geometryDirty = true
    }

    fun setCrop(mode: ScalingMode, focusX: Float, focusY: Float) {
        if (mode == scalingMode && focusX == this.focusX && focusY == this.focusY) return
        scalingMode = mode
        this.focusX = focusX
        this.focusY = focusY
        geometryDirty = true
    }

    /**
     * Uploads one composited GIF frame ([argbPixels], row-major, top row
     * first). Creates the GIF shader, texture and staging bitmap on first use
     * or when the size changes. Returns false if GL setup failed.
     */
    fun uploadGifFrame(argbPixels: IntArray, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0 || argbPixels.size < width * height) return false
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false
        if (gifProgram.id == 0 && !gifProgram.build(PLAIN_VERTEX_SHADER, GIF_FRAGMENT_SHADER)) {
            Log.e(TAG, "GIF shader failed")
            return false
        }
        val allocate = gifTextureId == 0 || gifBitmap == null || gifWidth != width || gifHeight != height
        if (allocate) {
            releaseGifTexture()
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            if (ids[0] == 0) return false
            gifTextureId = ids[0]
            configureBoundTexture(GLES20.GL_TEXTURE_2D, gifTextureId)
            gifBitmap = try {
                createBitmap(width, height)
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "Out of memory for ${width}x$height GIF bitmap", e)
                releaseGifTexture()
                return false
            }
            gifWidth = width
            gifHeight = height
            geometryDirty = true
        }
        val bitmap = gifBitmap ?: return false
        bitmap.setPixels(argbPixels, 0, width, 0, 0, width, height)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, gifTextureId)
        if (allocate) {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        } else {
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
        }
        return true
    }

    /**
     * Draws the current content (or black) at [viewportWidth] x
     * [viewportHeight] and presents it. Returns false if presenting failed;
     * [lastEglError] then says why (EGL_CONTEXT_LOST means the renderer must
     * be rebuilt, EGL_BAD_SURFACE / EGL_BAD_NATIVE_WINDOW that the surface is
     * gone).
     */
    fun draw(viewportWidth: Int, viewportHeight: Int): Boolean {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || viewportWidth <= 0 || viewportHeight <= 0) return true

        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        when (content) {
            Content.VIDEO -> if (hasVideoFrame && videoProgram.id != 0) {
                updateGeometryIfNeeded(viewportWidth, viewportHeight, videoWidth, videoHeight)
                GLES20.glUseProgram(videoProgram.id)
                GLES20.glUniformMatrix4fv(texMatrixHandle, 1, false, texMatrix, 0)
                drawQuad(videoProgram, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId)
            }
            Content.GIF -> if (gifTextureId != 0 && gifProgram.id != 0) {
                updateGeometryIfNeeded(viewportWidth, viewportHeight, gifWidth, gifHeight)
                GLES20.glUseProgram(gifProgram.id)
                drawQuad(gifProgram, GLES20.GL_TEXTURE_2D, gifTextureId)
            }
            Content.NONE -> Unit
        }

        if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
            lastEglError = EGL14.eglGetError()
            Log.w(TAG, "eglSwapBuffers failed: 0x${Integer.toHexString(lastEglError)}")
            return false
        }
        lastEglError = EGL14.EGL_SUCCESS
        return true
    }

    private fun updateGeometryIfNeeded(viewW: Int, viewH: Int, contentW: Int, contentH: Int) {
        if (!geometryDirty && viewW == lastViewportWidth && viewH == lastViewportHeight) return
        val q = CropGeometry.compute(viewW, viewH, contentW, contentH, scalingMode, focusX, focusY)
        // Triangle strip: bottom-left, bottom-right, top-left, top-right.
        // Each vertex: x, y, u, v (v = 0 at the top of the picture).
        vertexBuffer.position(0)
        vertexBuffer.put(
            floatArrayOf(
                q.left, q.bottom, q.uLeft, q.vBottom,
                q.right, q.bottom, q.uRight, q.vBottom,
                q.left, q.top, q.uLeft, q.vTop,
                q.right, q.top, q.uRight, q.vTop
            )
        )
        vertexBuffer.position(0)
        lastViewportWidth = viewW
        lastViewportHeight = viewH
        geometryDirty = false
    }

    private fun drawQuad(program: Program, target: Int, textureId: Int) {
        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(program.position, 2, GLES20.GL_FLOAT, false, STRIDE_BYTES, vertexBuffer)
        GLES20.glEnableVertexAttribArray(program.position)
        vertexBuffer.position(2)
        GLES20.glVertexAttribPointer(program.texCoord, 2, GLES20.GL_FLOAT, false, STRIDE_BYTES, vertexBuffer)
        GLES20.glEnableVertexAttribArray(program.texCoord)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(target, textureId)
        GLES20.glUniform1i(program.sampler, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(program.position)
        GLES20.glDisableVertexAttribArray(program.texCoord)
    }

    private fun releaseGifTexture() {
        if (gifTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(gifTextureId), 0)
            gifTextureId = 0
        }
        gifBitmap?.recycle()
        gifBitmap = null
        gifWidth = 0
        gifHeight = 0
    }

    /** Frees everything. Safe to call more than once, and after a failed [start]. */
    fun release() {
        videoInputSurface?.release()
        videoInputSurface = null
        videoSurfaceTexture?.setOnFrameAvailableListener(null)
        videoSurfaceTexture?.release()
        videoSurfaceTexture = null
        if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglContext != EGL14.EGL_NO_CONTEXT) {
            // GL objects can only be deleted while the context is current.
            if (videoTextureId != 0) GLES20.glDeleteTextures(1, intArrayOf(videoTextureId), 0)
            releaseGifTexture()
            videoProgram.delete()
            gifProgram.delete()
        }
        videoTextureId = 0
        gifBitmap?.recycle()
        gifBitmap = null
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            // Balances our eglInitialize. Android reference-counts these, so
            // other renderers in the process (preview, other engines) keep
            // their display.
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    private fun chooseConfig(): EGLConfig? {
        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, count, 0) || count[0] <= 0) return null
        return configs[0]
    }

    /** A linked shader program with the attribute/uniform names both shaders share. */
    private class Program {
        var id = 0
        var position = -1
        var texCoord = -1
        var sampler = -1

        fun build(vertexSrc: String, fragmentSrc: String): Boolean {
            val vs = compile(GLES20.GL_VERTEX_SHADER, vertexSrc)
            if (vs == 0) return false
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
            if (fs == 0) {
                GLES20.glDeleteShader(vs)
                return false
            }
            val program = GLES20.glCreateProgram()
            if (program == 0) {
                GLES20.glDeleteShader(vs)
                GLES20.glDeleteShader(fs)
                return false
            }
            GLES20.glAttachShader(program, vs)
            GLES20.glAttachShader(program, fs)
            GLES20.glLinkProgram(program)
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            val status = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                Log.e(TAG, "Link failed: ${GLES20.glGetProgramInfoLog(program)}")
                GLES20.glDeleteProgram(program)
                return false
            }
            val pos = GLES20.glGetAttribLocation(program, "aPosition")
            val tex = GLES20.glGetAttribLocation(program, "aTexCoord")
            val smp = GLES20.glGetUniformLocation(program, "uTexture")
            if (pos < 0 || tex < 0 || smp < 0) {
                Log.e(TAG, "Missing shader attribute/uniform")
                GLES20.glDeleteProgram(program)
                return false
            }
            id = program
            position = pos
            texCoord = tex
            sampler = smp
            return true
        }

        fun delete() {
            if (id != 0) GLES20.glDeleteProgram(id)
            id = 0
        }

        private fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            if (shader == 0) return 0
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                Log.e(TAG, "Compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
                GLES20.glDeleteShader(shader)
                return 0
            }
            return shader
        }
    }

    companion object {
        private const val TAG = "VideoFrameRenderer"

        // 4 floats per vertex (x, y, u, v), 4 bytes per float.
        private const val STRIDE_BYTES = 4 * 4

        // GIF texture rows are uploaded top row first, so v (0 = top) is
        // already the right texture coordinate.
        private const val PLAIN_VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
        """

        // SurfaceTexture's matrix expects GL's convention (v = 0 at the
        // bottom), so v is flipped first; the matrix then applies the
        // video's rotation, flip and padding crop.
        private const val VIDEO_VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTexCoord = (uTexMatrix * vec4(aTexCoord.x, 1.0 - aTexCoord.y, 0.0, 1.0)).xy;
            }
        """

        // The #extension line is required to sample a samplerExternalOES.
        private const val VIDEO_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """

        // Alpha forced to 1: GIF transparency is drawn as black (transparent
        // pixels are stored as rgb 0), and a translucent surface could
        // otherwise show whatever is behind it.
        private const val GIF_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            void main() {
                gl_FragColor = vec4(texture2D(uTexture, vTexCoord).rgb, 1.0);
            }
        """
    }
}
