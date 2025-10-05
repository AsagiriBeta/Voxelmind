package asagiribeta.voxelmind.client.util;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import asagiribeta.voxelmind.config.Config;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class ScreenshotUtil {
    private ScreenshotUtil() {}

    private static final Logger LOGGER = LogManager.getLogger("VoxelMind-SS");

    private static volatile StrategyType lastStrategy = StrategyType.UNSUPPORTED;
    private static volatile boolean lastManual = false;
    private static volatile int lastBytes = -1;
    private static volatile String lastError = null;
    private static volatile boolean reflectionBroken = false; // when true, skip reflection strategy entirely
    private static volatile long lastReflectionWarnTick = 0L; // rate-limit warning logs

    public static String debugInfo() {
        return "strategy=" + lastStrategy + ", manual=" + lastManual + ", bytes=" + lastBytes + (lastError!=null? (", err=" + lastError):"");
    }

    // Strategy caching
    private static volatile Strategy cachedStrategy = null;
    private enum StrategyType { RT_CONSUMER, RT_INT_CONSUMER, RT_RETURNS_IMAGE, PATH_RT_CONSUMER, PATH_RT_INT_CONSUMER, UNSUPPORTED }
    private record Strategy(StrategyType type, Method method) {}

    public static byte[] captureToPngBytes(Minecraft mc) {
        try {
            lastManual = false; lastBytes = -1; lastError = null; lastStrategy = StrategyType.UNSUPPORTED;
            RenderTarget rt = mc.getMainRenderTarget();
            if (rt == null || rt.width <= 0 || rt.height <= 0) { lastError = "rt-null-or-zero"; return null; }
            AtomicReference<byte[]> ref = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);
            Consumer<NativeImage> cb = img -> {
                try (img) {
                    byte[] bytes = encodeNativeImage(img);
                    ref.set(bytes);
                } catch (Throwable t) {
                    if (Config.get().debug()) LOGGER.warn("[VoxelMind] Callback encode failed: {}", t.toString());
                } finally { latch.countDown(); }
            };
            boolean invoked = invokeScreenshot(rt, cb);
            if (!invoked) {
                lastManual = true;
                byte[] fb = manualFallback(rt);
                if (fb != null) lastBytes = fb.length; else lastError = "manual-null";
                return fb;
            }
            // Intentionally ignore boolean return (timeout) here; we will fall back if ref not set.
            latch.await(500, TimeUnit.MILLISECONDS);
            byte[] out = ref.get();
            if (out == null) {
                lastManual = true;
                out = manualFallback(rt);
                if (out != null) lastBytes = out.length; else lastError = "manual-after-null";
            } else lastBytes = out.length;
            return out;
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName();
            if (Config.get().debug()) LOGGER.error("[VoxelMind] captureToPngBytes fatal: {}", t.toString());
            return null;
        }
    }

    public static void captureAsync(Minecraft mc, Consumer<byte[]> onPngBytes) {
        try {
            lastManual = false; lastBytes = -1; lastError = null; lastStrategy = StrategyType.UNSUPPORTED;
            RenderTarget rt = mc.getMainRenderTarget();
            if (rt == null || rt.width <= 0 || rt.height <= 0) { lastError = "rt-null-or-zero"; onPngBytes.accept(null); return; }
            Consumer<NativeImage> cb = img -> {
                byte[] data = null;
                try (img) { data = encodeNativeImage(img); } catch (Throwable t) { if (Config.get().debug()) LOGGER.warn("[VoxelMind] Async encode failed: {}", t.toString()); }
                if (data == null) {
                    try { lastManual = true; data = manualFallback(rt); if (data!=null) lastBytes = data.length; else lastError = "manual-null"; } catch (Throwable ignored) {}
                } else lastBytes = data.length;
                try { onPngBytes.accept(data); } catch (Throwable ignored) {}
            };
            boolean ok = invokeScreenshot(rt, cb);
            if (!ok) {
                lastManual = true;
                byte[] fb = manualFallback(rt);
                if (fb != null) lastBytes = fb.length; else lastError = "manual-null";
                onPngBytes.accept(fb);
            }
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName();
            if (Config.get().debug()) LOGGER.error("[VoxelMind] captureAsync fatal: {}", t.toString());
            onPngBytes.accept(null);
        }
    }

    private static Strategy resolveStrategy() {
        Strategy local = cachedStrategy; if (local != null) return local;
        synchronized (ScreenshotUtil.class) {
            if (cachedStrategy != null) return cachedStrategy;
            Strategy decided = null;
            try { var m = Screenshot.class.getMethod("takeScreenshot", RenderTarget.class, Consumer.class); m.setAccessible(true); decided = new Strategy(StrategyType.RT_CONSUMER, m);} catch (NoSuchMethodException ignored) {}
            if (decided == null) { try { var m = Screenshot.class.getMethod("takeScreenshot", RenderTarget.class, int.class, Consumer.class); m.setAccessible(true); decided = new Strategy(StrategyType.RT_INT_CONSUMER, m);} catch (NoSuchMethodException ignored) {} }
            if (decided == null) { try { var m = Screenshot.class.getMethod("takeScreenshot", RenderTarget.class); m.setAccessible(true); decided = new Strategy(StrategyType.RT_RETURNS_IMAGE, m);} catch (NoSuchMethodException ignored) {} }
            if (decided == null) {
                for (Method m : Screenshot.class.getDeclaredMethods()) {
                    if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue; var p = m.getParameterTypes();
                    if (p.length==2 && RenderTarget.class.isAssignableFrom(p[0]) && Consumer.class.isAssignableFrom(p[1])) { m.setAccessible(true); decided = new Strategy(StrategyType.RT_CONSUMER, m); break; }
                    if (p.length==3 && RenderTarget.class.isAssignableFrom(p[0]) && p[1]==int.class && Consumer.class.isAssignableFrom(p[2])) { m.setAccessible(true); decided = new Strategy(StrategyType.RT_INT_CONSUMER, m); break; }
                }
            }
            if (decided == null) {
                for (Method m : Screenshot.class.getDeclaredMethods()) {
                    if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                    if (m.getParameterCount()==1 && RenderTarget.class.isAssignableFrom(m.getParameterTypes()[0]) && NativeImage.class.isAssignableFrom(m.getReturnType())) { m.setAccessible(true); decided = new Strategy(StrategyType.RT_RETURNS_IMAGE, m); break; }
                }
            }
            if (decided == null) decided = new Strategy(StrategyType.UNSUPPORTED, null);
            cachedStrategy = decided; lastStrategy = decided.type();
            if (Config.get().debug()) LOGGER.info("[VoxelMind] Screenshot strategy resolved: {} (genericScan={})", decided.type(), decided.type()==StrategyType.UNSUPPORTED?"NONE":"OK");
            if (decided.type() == StrategyType.UNSUPPORTED) LOGGER.warn("[VoxelMind] No compatible screenshot method signature found; captures rely solely on manual fallback.");
            return decided;
        }
    }

    private static boolean invokeScreenshot(RenderTarget rt, Consumer<NativeImage> cb) {
        if (reflectionBroken) return false;
        Strategy strat = resolveStrategy(); lastStrategy = strat.type();
        try {
            switch (strat.type) {
                case RT_CONSUMER -> { strat.method.invoke(null, rt, cb); return true; }
                case RT_INT_CONSUMER -> { strat.method.invoke(null, rt, 0, cb); return true; }
                case RT_RETURNS_IMAGE -> { NativeImage img = (NativeImage) strat.method.invoke(null, rt); cb.accept(img); return true; }
                case PATH_RT_CONSUMER, PATH_RT_INT_CONSUMER -> { if (Config.get().debug()) LOGGER.info("[VoxelMind] Skipping PATH_RT* screenshot method; using manual fallback"); return false; }
                case UNSUPPORTED -> { return false; }
            }
        } catch (Throwable t) {
            reflectionBroken = true; cachedStrategy = null; lastError = "invoke-exception";
            if (Config.get().debug()) {
                long now = System.currentTimeMillis();
                if (now - lastReflectionWarnTick > 5000) {
                    lastReflectionWarnTick = now;
                    LOGGER.warn("[VoxelMind] Disabling reflection screenshot path after exception: {}. Will use manual fallback only.", t.getClass().getSimpleName());
                }
            }
        }
        return false;
    }

    private static volatile Method pixelGetterCache;
    private static byte[] encodeNativeImage(NativeImage img) throws Exception {
        if (img == null) return null;
        int w = img.getWidth(); int h = img.getHeight(); if (w<=0 || h<=0) return null;
        Method getter = resolvePixelGetter(img.getClass());
        int[] argb = new int[w*h];
        if (getter != null) {
            for (int y=0;y<h;y++) {
                for (int x=0;x<w;x++) {
                    int c = (int)getter.invoke(img,x,y);
                    argb[y*w+x] = c;
                }
            }
        } else {
            throw new IllegalStateException("No pixel getter");
        }
        return encodeARGBToPNG(w,h,argb);
    }

    private static Method resolvePixelGetter(Class<?> cls) {
        Method local = pixelGetterCache; if (local != null) return local;
        synchronized (ScreenshotUtil.class) {
            if (pixelGetterCache != null) return pixelGetterCache;
            try { var m = cls.getMethod("getPixelRGBA", int.class, int.class); m.setAccessible(true); pixelGetterCache = m; return m; } catch (NoSuchMethodException ignored) {}
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getParameterCount()==2 && m.getReturnType()==int.class) {
                    var p = m.getParameterTypes();
                    if (p[0]==int.class && p[1]==int.class) { m.setAccessible(true); pixelGetterCache = m; return m; }
                }
            }
            return null;
        }
    }

    private static byte[] encodeARGBToPNG(int w, int h, int[] argb) throws Exception {
        BufferedImage bi = new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);
        bi.setRGB(0,0,w,h,argb,0,w);
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(bi, "png", baos);
            return baos.toByteArray();
        }
    }

    private static byte[] manualFallback(RenderTarget rt) {
        try {
            int w = rt.width; int h = rt.height; if (w <= 0 || h <= 0) return null;
            int bytes = w * h * 4; var buf = BufferUtils.createByteBuffer(bytes);
            GL11.glReadPixels(0,0,w,h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
            int[] argb = new int[w*h];
            for (int y=0;y<h;y++) {
                int srcY = h-1-y;
                for (int x=0;x<w;x++) {
                    int i = (srcY*w + x)*4;
                    int r = buf.get(i) & 0xFF;
                    int g = buf.get(i+1) & 0xFF;
                    int b = buf.get(i+2) & 0xFF;
                    int a = buf.get(i+3) & 0xFF;
                    argb[y*w+x] = (a<<24)|(r<<16)|(g<<8)|b;
                }
            }
            return encodeARGBToPNG(w,h,argb);
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName();
            if (Config.get().debug()) LOGGER.warn("[VoxelMind] manualFallback failed: {}", t.toString());
            return null;
        }
    }
}
