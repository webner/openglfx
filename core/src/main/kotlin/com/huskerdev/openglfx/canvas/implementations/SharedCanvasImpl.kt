package com.huskerdev.openglfx.canvas.implementations

import com.huskerdev.ojgl.GLContext
import com.huskerdev.openglfx.*
import com.huskerdev.openglfx.GLExecutor.Companion.glFinish
import com.huskerdev.openglfx.GLExecutor.Companion.glViewport
import com.huskerdev.openglfx.canvas.GLProfile
import com.huskerdev.openglfx.canvas.OpenGLCanvas
import com.huskerdev.openglfx.internal.fbo.MultiSampledFramebuffer
import com.huskerdev.openglfx.internal.GLFXUtils.Companion.GLTextureId
import com.huskerdev.openglfx.internal.GLInteropType
import com.huskerdev.openglfx.internal.fbo.Framebuffer
import com.sun.javafx.scene.DirtyBits
import com.sun.javafx.scene.NodeHelper
import com.sun.prism.Graphics
import com.sun.prism.GraphicsPipeline
import com.sun.prism.PixelFormat
import com.sun.prism.Texture
import javafx.animation.AnimationTimer
import java.util.concurrent.atomic.AtomicBoolean

open class SharedCanvasImpl(
    private val executor: GLExecutor,
    profile: GLProfile,
    flipY: Boolean,
    msaa: Int
): OpenGLCanvas(GLInteropType.TextureSharing, profile, flipY, msaa, false){

    private var lastSize = Pair(-1, -1)

    private var context: GLContext? = null
    private var fxContext: GLContext? = null

    private lateinit var fbo: Framebuffer
    private lateinit var msaaFBO: MultiSampledFramebuffer

    private var fxTexture: Texture? = null

    private var needsRepaint = AtomicBoolean(false)

    init {
        visibleProperty().addListener { _, _, _ -> repaint() }
        widthProperty().addListener { _, _, _ -> repaint() }
        heightProperty().addListener { _, _, _ -> repaint() }

        object: AnimationTimer(){
            override fun handle(now: Long) {
                if(needsRepaint.getAndSet(false)) {
                    NodeHelper.markDirty(this@SharedCanvasImpl, DirtyBits.NODE_BOUNDS)
                    NodeHelper.markDirty(this@SharedCanvasImpl, DirtyBits.REGION_SHAPE)
                }
            }
        }.start()
    }

    override fun onNGRender(g: Graphics) {
        if (context == null) {
            fxContext = GLContext.current()
            context = GLContext.create(fxContext!!, profile == GLProfile.Core)
            executor.initGLFunctions()
        }
        context!!.makeCurrent()

        if (scaledWidth.toInt() != lastSize.first || scaledHeight.toInt() != lastSize.second) {
            lastSize = Pair(scaledWidth.toInt(), scaledHeight.toInt())

            updateFramebufferSize()
            fireReshapeEvent(lastSize.first, lastSize.second)
        }

        glViewport(0, 0, lastSize.first, lastSize.second)
        fireRenderEvent(if(msaa != 0) msaaFBO.id else fbo.id)
        if(msaa != 0)
            msaaFBO.blitTo(fbo.id)

        glFinish()
        fxContext!!.makeCurrent()

        drawResultTexture(g, fxTexture!!)
    }

    private fun updateFramebufferSize() {
        if(::fbo.isInitialized){
            fbo.delete()
            if(msaa != 0) msaaFBO.delete()
        }

        val width = lastSize.first
        val height = lastSize.second

        fxContext!!.makeCurrent()
        // Create JavaFX texture
        fxTexture?.dispose()
        fxTexture = GraphicsPipeline.getDefaultResourceFactory().createTexture(PixelFormat.BYTE_BGRA_PRE, Texture.Usage.DYNAMIC, Texture.WrapMode.CLAMP_TO_EDGE, width, height)
        fxTexture!!.makePermanent()
        context!!.makeCurrent()

        // Create framebuffer that connected to JavaFX's texture
        fbo = Framebuffer(width, height, existingTexture = fxTexture!!.GLTextureId)
        fbo.bindFramebuffer()

        // Create multi-sampled framebuffer
        if(msaa != 0){
            msaaFBO = MultiSampledFramebuffer(msaa, lastSize.first, lastSize.second)
            msaaFBO.bindFramebuffer()
        }
    }

    override fun repaint() = needsRepaint.set(true)

    override fun dispose() {
        super.dispose()
        GLContext.delete(context!!)
    }
}