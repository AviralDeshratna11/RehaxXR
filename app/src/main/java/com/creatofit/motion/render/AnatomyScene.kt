package com.creatofit.motion.render

import android.view.Surface
import android.view.TextureView
import com.google.android.filament.*
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.*
import java.nio.Buffer

/**
 * Filament scene for the avatar: opaque light background matching the card, a soft key
 * light with shadows, a cool fill light, uniform ambient light, MSAA and filmic tone mapping.
 * The app owns the render loop so diagnostics count real render submissions.
 */
class AnatomyScene(private val surface: TextureView) {
    val engine: Engine = Engine.create()
    val renderer: Renderer = engine.createRenderer()
    private val scene = engine.createScene()
    val camera: Camera = engine.createCamera(EntityManager.get().create())
    val view: View = engine.createView().apply { scene = this@AnatomyScene.scene; camera = this@AnatomyScene.camera }
    private val materials = UbershaderProvider(engine)
    private val loader = AssetLoader(engine, materials, EntityManager.get())
    private val resources = ResourceLoader(engine, true)
    private val helper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK).apply { isOpaque = true }
    private val display = DisplayHelper(surface.context)
    private var swap: SwapChain? = null
    private val ready = IntArray(128)
    private val sun = EntityManager.get().create()
    private val fill = EntityManager.get().create()
    private val ambient: IndirectLight
    private val grading: ColorGrading
    var asset: FilamentAsset? = null; private set
    /** Called once per renderable as it becomes ready (shadow flags are set here). */
    var onRenderableAdded: ((Int) -> Unit)? = null

    init {
        // Opaque surface (transparent GL surfaces composite unreliably on some GPUs); the clear colour
        // matches the card background (#F3F4F6) in linear space.
        renderer.clearOptions = Renderer.ClearOptions().apply { clear = true; clearColor = floatArrayOf(.896f, .904f, .922f, 1f) }
        view.antiAliasing = View.AntiAliasing.FXAA
        view.multiSampleAntiAliasingOptions = View.MultiSampleAntiAliasingOptions().apply { enabled = true; sampleCount = 4 }
        view.ambientOcclusionOptions = View.AmbientOcclusionOptions().apply { enabled = true; radius = .25f; intensity = .8f }
        view.setShadowType(View.ShadowType.PCF)
        grading = ColorGrading.Builder().toneMapper(ToneMapper.Linear()).build(engine)
        view.setColorGrading(grading)
        // Unit exposure + linear tone mapping: authored colours (and the clear colour) reach the screen unchanged.
        camera.setExposure(1f)

        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1f, .97f, .93f).intensity(2.3f).direction(.35f, -1f, -.55f)
            .castShadows(true)
            .shadowOptions(LightManager.ShadowOptions().apply { mapSize = 1024; constantBias = .002f; normalBias = 1.2f })
            .build(engine, sun)
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(.86f, .92f, 1f).intensity(.75f).direction(-.6f, -.3f, .45f).castShadows(false)
            .build(engine, fill)
        scene.addEntity(sun); scene.addEntity(fill)
        ambient = IndirectLight.Builder().irradiance(1, floatArrayOf(1f, 1f, 1f)).intensity(.36f).build(engine)
        scene.indirectLight = ambient

        helper.renderCallback = object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(window: Surface) {
                swap?.let { engine.destroySwapChain(it) }
                swap = engine.createSwapChain(window, helper.swapChainFlags)
                display.attach(renderer, surface.display)
            }
            override fun onDetachedFromSurface() {
                display.detach()
                swap?.let { engine.destroySwapChain(it); engine.flushAndWait() }; swap = null
            }
            override fun onResized(width: Int, height: Int) { view.viewport = Viewport(0, 0, width, height); engine.flushAndWait() }
        }
        helper.attachTo(surface)
    }

    fun loadModelGlb(buffer: Buffer) {
        asset = checkNotNull(loader.createAsset(buffer)) { "Could not load avatar GLB" }
        resources.asyncBeginLoad(asset!!)
        asset!!.releaseSourceData()
    }

    fun render(timestamp: Long): Boolean {
        if (!helper.isReadyToRender) return false
        resources.asyncUpdateLoad()
        asset?.let { a ->
            var count = a.popRenderables(ready)
            while (count > 0) {
                val batch = ready.copyOf(count)
                batch.forEach { onRenderableAdded?.invoke(it) }
                scene.addEntities(batch)
                count = a.popRenderables(ready)
            }
        }
        val chain = swap ?: return false
        if (!renderer.beginFrame(chain, timestamp)) return false
        renderer.render(view); renderer.endFrame(); return true
    }

    fun destroy() {
        helper.detach()
        resources.asyncCancelLoad(); resources.evictResourceData()
        asset?.let { scene.removeEntities(it.entities); loader.destroyAsset(it) }; asset = null
        loader.destroy(); materials.destroyMaterials(); materials.destroy(); resources.destroy()
        engine.destroyEntity(sun); engine.destroyEntity(fill)
        engine.destroyIndirectLight(ambient); engine.destroyColorGrading(grading)
        engine.destroyView(view); engine.destroyScene(scene); engine.destroyRenderer(renderer)
        engine.destroyCameraComponent(camera.entity); EntityManager.get().destroy(camera.entity)
        EntityManager.get().destroy(sun); EntityManager.get().destroy(fill)
        engine.destroy()
    }
}
