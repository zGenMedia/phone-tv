package com.zgenmedia.tvhome;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Draws the video bent to the screen for {@link Picture#PANORAMA}.
 *
 * An ordinary video surface can only be scaled evenly, so this mode takes another road:
 * the player decodes into a texture, and a small shader paints the screen from it,
 * reading across the picture quickly in the middle and slowly toward the sides. It is
 * only in use while Panorama is; every other mode keeps the plain surface, which costs
 * nothing and passes HDR straight through. (This road is ordinary colour only, which is
 * what a 4:3 programme is.)
 */
final class PanoramaView extends GLSurfaceView implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {
    interface Listener {
        /** Called on the main thread with somewhere for the player to decode to. May come again after the screen was away. */
        void onSurface(Surface surface);
        /** This device couldn't set the shader up. */
        void onFailed();
    }

    private static final String VERTEX =
            "attribute vec2 corner;\n"
            + "varying vec2 at;\n"
            + "void main() {\n"
            + "  at = corner * 0.5 + 0.5;\n"
            + "  gl_Position = vec4(corner, 0.0, 1.0);\n"
            + "}\n";

    // Picture.panoramaSource, in the shader's words. Keep the two the same.
    private static final String FRAGMENT =
            "#extension GL_OES_EGL_image_external : require\n"
            + "#ifdef GL_FRAGMENT_PRECISION_HIGH\n"
            + "precision highp float;\n"
            + "#else\n"
            + "precision mediump float;\n"
            + "#endif\n"
            + "uniform samplerExternalOES video;\n"
            + "uniform mat4 videoMatrix;\n"
            + "uniform float centre;\n"
            + "uniform float shown;\n"      // how much of the picture's height is on screen: 1 / zoom
            + "varying vec2 at;\n"
            + "void main() {\n"
            + "  float x = at.x * 2.0 - 1.0;\n"
            + "  float fromX = centre * x - (centre - 1.0) * x * abs(x) * abs(x);\n"
            + "  vec2 from = vec2(fromX * 0.5 + 0.5, (at.y - 0.5) * shown + 0.5);\n"
            + "  gl_FragColor = texture2D(video, (videoMatrix * vec4(from, 0.0, 1.0)).xy);\n"
            + "}\n";

    private final Listener listener;
    private final float[] videoMatrix = new float[16];
    private final FloatBuffer corners;
    private volatile float centre = 1f, shown = 1f;
    private SurfaceTexture frames;
    private Surface surface;
    private int program, texture, cornerAt, videoAt, matrixAt, centreAt, shownAt;

    PanoramaView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        corners = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        corners.put(new float[]{-1, -1, 1, -1, -1, 1, 1, 1}).position(0);
        setEGLContextClientVersion(2);
        setRenderer(this);
        setRenderMode(RENDERMODE_WHEN_DIRTY); // a frame is drawn when the player delivers one, not sixty times a second
    }

    /** @param widen the screen's shape over the picture's (see {@link Picture}) */
    void shape(float widen) {
        centre = Picture.panoramaCentre(widen);
        shown = 1f / Picture.panoramaZoom(widen);
        requestRender();
    }

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        program = link(VERTEX, FRAGMENT);
        if (program == 0) { post(listener::onFailed); return; }
        cornerAt = GLES20.glGetAttribLocation(program, "corner");
        videoAt = GLES20.glGetUniformLocation(program, "video");
        matrixAt = GLES20.glGetUniformLocation(program, "videoMatrix");
        centreAt = GLES20.glGetUniformLocation(program, "centre");
        shownAt = GLES20.glGetUniformLocation(program, "shown");

        int[] made = new int[1];
        GLES20.glGenTextures(1, made, 0);
        texture = made[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        // A new drawing context (first time, or after the app was in the background) means a new
        // texture, so the player gets a new place to decode to.
        final Surface old = surface;
        final SurfaceTexture oldFrames = frames;
        frames = new SurfaceTexture(texture);
        frames.setOnFrameAvailableListener(this);
        surface = new Surface(frames);
        final Surface fresh = surface;
        post(() -> {
            listener.onSurface(fresh);
            if (old != null) old.release();
            if (oldFrames != null) oldFrames.release();
        });
    }

    @Override public void onSurfaceChanged(GL10 unused, int width, int height) { GLES20.glViewport(0, 0, width, height); }

    @Override
    public void onDrawFrame(GL10 unused) {
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if (program == 0 || frames == null) return;
        try { frames.updateTexImage(); } catch (RuntimeException gone) { return; } // released under us: the next context makes a new one
        frames.getTransformMatrix(videoMatrix);
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
        GLES20.glUniform1i(videoAt, 0);
        GLES20.glUniformMatrix4fv(matrixAt, 1, false, videoMatrix, 0);
        GLES20.glUniform1f(centreAt, centre);
        GLES20.glUniform1f(shownAt, shown);
        GLES20.glEnableVertexAttribArray(cornerAt);
        GLES20.glVertexAttribPointer(cornerAt, 2, GLES20.GL_FLOAT, false, 0, corners);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(cornerAt);
    }

    @Override public void onFrameAvailable(SurfaceTexture texture) { requestRender(); }

    /** The player is done with it. */
    void release() {
        final Surface s = surface;
        final SurfaceTexture f = frames;
        surface = null;
        frames = null;
        if (s != null) s.release();
        if (f != null) f.release();
    }

    private static int link(String vertex, String fragment) {
        int v = compile(GLES20.GL_VERTEX_SHADER, vertex), f = compile(GLES20.GL_FRAGMENT_SHADER, fragment);
        if (v == 0 || f == 0) return 0;
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) { GLES20.glDeleteProgram(p); return 0; }
        return p;
    }

    private static int compile(int kind, String source) {
        int s = GLES20.glCreateShader(kind);
        GLES20.glShaderSource(s, source);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) { GLES20.glDeleteShader(s); return 0; }
        return s;
    }
}
