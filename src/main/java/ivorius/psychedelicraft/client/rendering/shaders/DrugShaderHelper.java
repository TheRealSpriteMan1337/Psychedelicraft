/*
 *  Copyright (c) 2014, Lukas Tenbrink.
 *  * http://lukas.axxim.net
 */

package ivorius.psychedelicraft.client.rendering.shaders;

import com.google.common.base.Charsets;
import ivorius.psychedelicraft.Psychedelicraft;
import ivorius.psychedelicraft.client.rendering.EntityFakeSun;
import ivorius.psychedelicraft.client.rendering.PsycheShadowHelper;
import ivorius.psychedelicraft.client.rendering.effectWrappers.*;
import ivorius.psychedelicraft.ivToolkit.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.IResource;
import net.minecraft.util.ResourceLocation;
import org.apache.commons.io.IOUtils;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

public class DrugShaderHelper
{
    public static ShaderWorld currentShader;
    public static ArrayList<ShaderWorld> worldShaders = new ArrayList<ShaderWorld>();
    public static ShaderMain shaderInstance;
    public static ShaderMainDepth shaderInstanceDepth;
    public static ShaderShadows shaderInstanceShadows;

    public static List<IEffectWrapper> effectWrappers = new ArrayList<IEffectWrapper>();

    public static IvOpenGLTexturePingPong realtimePingPong;

    public static IvDepthBuffer depthBuffer;

    public static boolean bypassFramebuffers = false;
    public static boolean shaderEnabled = true;
    public static boolean shader2DEnabled = true;
    public static boolean doShadows = false;
    public static boolean doHeatDistortion = false;
    public static boolean doWaterDistortion = false;

    public static float sunFlareIntensity;
    public static int shadowPixelsPerChunk = 256;

    public static String currentRenderPass;
    public static float currentRenderPassTicks;

    public static void preRender(float ticks)
    {
    }

    public static void preRender3D(float ticks)
    {

    }

    public static ArrayList<String> getRenderPasses(float partialTicks, float ticks)
    {
        Minecraft mc = Minecraft.getMinecraft();

        ArrayList<String> passes = new ArrayList<String>();

        passes.add("Default");

        if (depthBuffer.isAllocated() && shaderInstanceDepth.getShaderID() > 0)
        {
            boolean addDepth = false;

            for (IEffectWrapper wrapper : effectWrappers)
            {
                if (wrapper.wantsDepthBuffer())
                {
                    addDepth = true;
                }
            }

            if (addDepth)
            {
                passes.add("Depth");
            }
        }

        if (shaderInstanceShadows.depthBuffer.isAllocated() && shaderInstanceShadows.getShaderID() > 0 && doShadows)
        {
            passes.add("Shadows");
        }

        return passes;
    }

    public static boolean startRenderPass(String pass, float partialTicks, float ticks)
    {
        Minecraft mc = Minecraft.getMinecraft();

        if (currentRenderPass != null)
        {
            endRenderPass();
        }

        currentRenderPass = pass;
        currentRenderPassTicks = ticks;

        if ("Default".equals(pass))
        {
//            IvRenderHelper.drawRectFullScreen(mc);
//            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);

            shaderInstance.shouldDoShadows = doShadows;
            shaderInstance.shadowDepthTextureIndex = shaderInstanceShadows.depthBuffer.getDepthTextureIndex();

            return useShader(partialTicks, ticks, shaderInstance);
        }
        else if ("Depth".equals(pass))
        {
            depthBuffer.setParentFB(mc.getFramebuffer() != null ? mc.getFramebuffer().framebufferObject : 0);
            depthBuffer.setSize(mc.displayWidth, mc.displayHeight);
            depthBuffer.bindTextureForDestination();
            depthBuffer.bind();

            return useShader(partialTicks, ticks, shaderInstanceDepth);
        }
        else if ("Shadows".equals(pass))
        {
            Minecraft.getMinecraft().renderViewEntity = new EntityFakeSun(mc.renderViewEntity);
            return useShader(partialTicks, ticks, shaderInstanceShadows);
        }

        return true;
    }

    public static void endRenderPass()
    {
        if ("Default".equals(currentRenderPass))
        {

        }
        else if ("Depth".equals(currentRenderPass))
        {
            depthBuffer.unbind();
        }
        else if ("Shadows".equals(currentRenderPass))
        {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.renderViewEntity instanceof EntityFakeSun)
            {
                mc.renderViewEntity = ((EntityFakeSun) mc.renderViewEntity).prevViewEntity;
            }
        }

        if (currentShader != null)
        {
            currentShader.deactivate();
            currentShader = null;
        }

        IvOpenGLHelper.checkGLError(Psychedelicraft.logger, "Post render pass '" + currentRenderPass + "'");
        currentRenderPass = null;
    }

    public static boolean setupCameraTransform()
    {
        if ("Shadows".equals(currentRenderPass)/* || (Minecraft.getMinecraft().ingameGUI.getUpdateCounter() % 100 > 2)*/)
        {
            PsycheShadowHelper.setupSunGLTransform();

            return true;
        }

        return false;
    }

    public static void setShaderEnabled(boolean enabled)
    {
        shaderEnabled = enabled;
    }

    public static void setShader2DEnabled(boolean enabled)
    {
        shader2DEnabled = enabled;
    }

    public static void allocate()
    {
        Minecraft mc = Minecraft.getMinecraft();
        delete3DShaders();

        String utils = null;

        try
        {
            IResource utilsResource = Minecraft.getMinecraft().getResourceManager().getResource(new ResourceLocation(Psychedelicraft.MODID, Psychedelicraft.filePathShaders + "shaderUtils.frag"));
            utils = IOUtils.toString(utilsResource.getInputStream(), Charsets.UTF_8);
        }
        catch (Exception ex)
        {
            Psychedelicraft.logger.error("Could not load shader utils!", utils);
        }

        shaderInstance = new ShaderMain(Psychedelicraft.logger);
        setUpShader(shaderInstance, "shader3D.vert", "shader3D.frag", utils);
        worldShaders.add(shaderInstance);

        shaderInstanceDepth = new ShaderMainDepth(Psychedelicraft.logger);
        setUpShader(shaderInstanceDepth, "shader3D.vert", "shader3DDepth.frag", utils);
        worldShaders.add(shaderInstanceDepth);

        shaderInstanceShadows = new ShaderShadows(Psychedelicraft.logger);
        setUpShader(shaderInstanceShadows, "shader3D.vert", "shader3DDepth.frag", utils);
        worldShaders.add(shaderInstanceShadows);

        // Add order = Application order!
        effectWrappers.add(new WrapperHeatDistortion(utils));
        effectWrappers.add(new WrapperUnderwaterDistortion(utils));
        effectWrappers.add(new WrapperWaterOverlay(utils));
        effectWrappers.add(new WrapperSimpleEffects(utils));
        effectWrappers.add(new WrapperMotionBlur());
        effectWrappers.add(new WrapperBlur(utils));
        effectWrappers.add(new WrapperDoF(utils));
        effectWrappers.add(new WrapperRadialBlur(utils));
        effectWrappers.add(new WrapperBloom(utils));
        effectWrappers.add(new WrapperColorBloom(utils));
        effectWrappers.add(new WrapperDoubleVision(utils));
        effectWrappers.add(new WrapperBlurNoise(utils));
        effectWrappers.add(new WrapperDigital(utils));

        for (IEffectWrapper effectWrapper : effectWrappers)
        {
            effectWrapper.alloc();
        }

        setUpRealtimeCacheTexture();
        depthBuffer = new IvDepthBuffer(mc.displayWidth, mc.displayHeight, Psychedelicraft.logger);
        if (!bypassFramebuffers)
        {
            depthBuffer.allocate();
        }

        IvOpenGLHelper.checkGLError(Psychedelicraft.logger, "Allocation");
    }

    public static void setUpShader(IvShaderInstance shader, String vertexFile, String fragmentFile, String utils)
    {
        IvShaderInstanceMC.trySettingUpShader(shader, new ResourceLocation(Psychedelicraft.MODID, Psychedelicraft.filePathShaders + vertexFile), new ResourceLocation(Psychedelicraft.MODID, Psychedelicraft.filePathShaders + fragmentFile), utils);
    }

    public static void setUpRealtimeCacheTexture()
    {
        deleteRealtimeCacheTexture();

        realtimePingPong = new IvOpenGLTexturePingPong(Psychedelicraft.logger);
        realtimePingPong.setScreenSize(Minecraft.getMinecraft().displayWidth, Minecraft.getMinecraft().displayHeight);
        realtimePingPong.initialize(!bypassFramebuffers);
    }

    public static void update()
    {
        if (Minecraft.getMinecraft().theWorld != null)
        {
            for (IEffectWrapper effectWrapper : effectWrappers)
            {
                effectWrapper.update();
            }
        }
    }

    public static boolean useShader(float partialTicks, float ticks, ShaderWorld shader)
    {
        currentShader = null;

        if (shader != null && shaderEnabled)
        {
            if (shader.isShaderActive())
            {
                return true;
            }

            if (shader.activate(partialTicks, ticks))
            {
                currentShader = shader;
                return true;
            }
        }

        return false;
    }

    public static void setTexture2DEnabled(boolean enabled)
    {
        for (ShaderWorld shaderWorld : worldShaders)
        {
            shaderWorld.setTexture2DEnabled(enabled);
        }
    }

    public static void setLightmapEnabled(boolean enabled)
    {
        for (ShaderWorld shaderWorld : worldShaders)
        {
            shaderWorld.setLightmapEnabled(enabled);
        }
    }

    public static void setBlendFunc(int func)
    {
        for (ShaderWorld shaderWorld : worldShaders)
        {
            shaderWorld.setBlendFunc(func);
        }
    }

    public static void setOverrideColor(float... color)
    {
        if (color != null && color.length != 4)
        {
            throw new IllegalArgumentException("Color must be a length-4 float array");
        }

        for (ShaderWorld shaderWorld : worldShaders)
        {
            shaderWorld.setOverrideColor(color);
        }
    }

    public static void setGLLightEnabled(boolean enabled)
    {
        for (ShaderWorld shaderWorld : worldShaders)
        {
            shaderWorld.setGLLightEnabled(enabled);
        }
    }

    public static void setGLLight(int number, float x, float y, float z, float strength, float specular)
    {
        for (ShaderWorld shaderWorld : worldShaders)
        {
            shaderWorld.setGLLight(number, x, y, z, strength, specular);
        }
    }

    public static void setGLLightAmbient(float strength)
    {
        for (ShaderWorld shaderWorld : worldShaders)
        {
            shaderWorld.setGLLightAmbient(strength);
        }
    }

    public static void setFogMode(int mode)
    {
        for (ShaderWorld shaderWorld : worldShaders)
        {
            shaderWorld.setFogMode(mode);
        }
    }

    public static void setFogEnabled(boolean enabled)
    {
        for (ShaderWorld shaderWorld : worldShaders)
        {
            shaderWorld.setFogEnabled(enabled);
        }
    }

    public static void setDepthMultiplier(float depthMultiplier)
    {
        for (ShaderWorld shaderWorld : worldShaders)
        {
            shaderWorld.setDepthMultiplier(depthMultiplier);
        }
    }

    public static void setUseScreenTexCoords(boolean enabled)
    {
        for (ShaderWorld shaderWorld : worldShaders)
        {
            shaderWorld.setUseScreenTexCoords(enabled);
        }
    }

    public static void setScreenSizeDefault()
    {
        Minecraft mc = Minecraft.getMinecraft();
        setScreenSize(mc.displayWidth, mc.displayHeight);
    }

    public static void setScreenSize(float screenWidth, float screenHeight)
    {
        setPixelSize(1.0f / screenWidth, 1.0f / screenHeight);
    }

    public static void setPixelSize(float pixelWidth, float pixelHeight)
    {
        for (ShaderWorld shaderWorld : worldShaders)
        {
            shaderWorld.setPixelSize(pixelWidth, pixelHeight);
        }
    }

    public static void setProjectShadows(boolean projectShadows)
    {
        for (ShaderWorld shaderWorld : worldShaders)
        {
            shaderWorld.setProjectShadows(projectShadows);
        }
    }

    public static void postRender(float ticks, float partialTicks)
    {
        apply2DShaders(ticks, partialTicks);
    }

    public static void apply2DShaders(float ticks, float partialTicks)
    {
        Minecraft mc = Minecraft.getMinecraft();

        int screenWidth = mc.displayWidth;
        int screenHeight = mc.displayHeight;

        IvOpenGLHelper.setUpOpenGLStandard2D(screenWidth, screenHeight);
        GL11.glColor3f(1.0f, 1.0f, 1.0f);

        realtimePingPong.setParentFrameBuffer(mc.getFramebuffer() != null ? mc.getFramebuffer().framebufferObject : 0);
        realtimePingPong.preTick(screenWidth, screenHeight);

        for (IEffectWrapper effectWrapper : effectWrappers)
        {
            effectWrapper.apply(partialTicks, realtimePingPong);
        }

        realtimePingPong.postTick();

        IvOpenGLHelper.checkGLError(Psychedelicraft.logger, "2D Shaders");
    }

    public static int getTextureIndex(ResourceLocation loc)
    {
        TextureManager tm = Minecraft.getMinecraft().renderEngine;
        tm.bindTexture(loc); // Allocate texture. MOJANG!
        ITextureObject texture = tm.getTexture(loc);
        return texture.getGlTextureId();
    }

    public static void delete3DShaders()
    {
        if (shaderInstance != null)
        {
            shaderInstance.deleteShader();
        }
        shaderInstance = null;

        if (shaderInstanceDepth != null)
        {
            shaderInstanceDepth.deleteShader();
        }
        shaderInstanceDepth = null;

        if (shaderInstanceShadows != null)
        {
            shaderInstanceShadows.deleteShader();
        }
        shaderInstanceShadows = null;
    }

    private static void deleteEffect(Iv2DScreenEffect instance)
    {
        if (instance != null)
        {
            instance.destruct();
        }
    }

    public static void deleteRealtimeCacheTexture()
    {
        if (realtimePingPong != null)
        {
            realtimePingPong.destroy();
            realtimePingPong = null;
        }
    }

    public static void deallocate()
    {
        delete3DShaders();
        deleteRealtimeCacheTexture();

        for (IEffectWrapper effectWrapper : effectWrappers)
        {
            effectWrapper.dealloc();
        }
        effectWrappers.clear();

        if (depthBuffer != null)
        {
            depthBuffer.deallocate();
        }
        depthBuffer = null;

        worldShaders.clear();
    }

    public static void outputShaderInfo()
    {
        Psychedelicraft.logger.info("Graphics card info: ");
        IvShaderInstance.outputShaderInfo(Psychedelicraft.logger);
    }
}