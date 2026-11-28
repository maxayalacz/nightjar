package com.limelight.ui;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RecordingCanvas;
import android.graphics.Region;
import android.graphics.RenderNode;
import android.os.Build;
import android.util.AttributeSet;
import android.view.ViewParent;
import android.widget.FrameLayout;

import androidx.annotation.RequiresApi;

import com.limelight.R;

/**
 * A piece of glass: back to front, shadow, the blurred backdrop or the baked ambient or an opaque fill, depending on
 * the tier, the fill, and the rim. Its children sit on top. Must be a descendant of a
 * {@link GlassHost}, but not within the host's first child; without a host it draws its fill.
 */
public class GlassSurface extends FrameLayout {
    /** The blur works on the content at this fraction of its size. */
    static final int BLUR_SCALE = 4;

    private final String material;
    private GlassHost host;
    private GlassSpec spec;
    private GlassTier tier;
    private boolean day;
    private float radius = -1f;
    private final int[] at = new int[2];
    private final int[] hostAt = new int[2];
    private int atX;
    private int atY;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hairlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint bakedPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Path shape = new Path();
    private final Path rimShape = new Path();

    private Object glassNode;
    private boolean nodesStale = true;

    public GlassSurface(Context context) {
        this(context, null);
    }

    public GlassSurface(Context context, AttributeSet attrs) {
        super(context, attrs);
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.GlassSurface);
        String name = a.getString(R.styleable.GlassSurface_glassMaterial);
        material = name == null ? "chrome" : name;
        radius = a.getDimension(R.styleable.GlassSurface_glassRadius, -1f);
        a.recycle();
        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);
        rimPaint.setStyle(Paint.Style.STROKE);
        hairlinePaint.setStyle(Paint.Style.STROKE);
        setOutlineProvider(null);
    }

    public void setCornerRadius(float radius) {
        this.radius = radius;
        rebuild();
    }

    /** The current tier as this surface draws it. */
    public GlassTier tier() {
        return tier;
    }

    private GlassHost findHost() {
        ViewParent parent = getParent();
        while (parent != null) {
            if (parent instanceof GlassHost) return (GlassHost) parent;
            parent = parent.getParent();
        }
        return null;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        host = findHost();
        if (host != null) host.register(this);
        onTierChanged();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (host != null) host.unregister(this);
        host = null;
        glassNode = null;
    }

    void onTierChanged() {
        GlassTier now = host != null ? host.tier() : AppTheme.isHighContrast(getContext()) ? GlassTier.SOLID : GlassTier.BAKED;
        tier = now;
        day = AppTheme.look(getContext()) == AppTheme.Look.DAY;
        spec = GlassSpec.of(getContext(), material, now, day);
        fillPaint.setColor(spec.fill);
        shadowPaint.setColor(spec.shadowColor);
        hairlinePaint.setColor(spec.hairline);
        hairlinePaint.setStrokeWidth(Math.max(1f, getResources().getDisplayMetrics().density * 0.5f));
        rimPaint.setStrokeWidth(spec.rimWidth);
        bakedPaint.setColorFilter(new ColorMatrixColorFilter(GlassHost.glassMatrix(1f, spec.brightness)));
        nodesStale = true;
        rebuild();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rebuild();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (host == null) return;
        getLocationInWindow(at);
        host.getLocationInWindow(hostAt);
        // A transform in flight on this view is not part of where its backdrop lies.
        int x = at[0] - hostAt[0] - Math.round(getTranslationX());
        int y = at[1] - hostAt[1] - Math.round(getTranslationY());
        if (x != atX || y != atY) {
            atX = x;
            atY = y;
            nodesStale = true;
            invalidate();
        }
    }

    private float cornerRadius() {
        float half = Math.min(getWidth(), getHeight()) / 2f;
        return radius < 0f ? half : Math.min(radius, half);
    }

    private void rebuild() {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0 || spec == null) return;
        float r = cornerRadius();
        shape.reset();
        shape.addRoundRect(0, 0, w, h, r, r, Path.Direction.CW);
        float inset = spec.rimWidth / 2f;
        rimShape.reset();
        rimShape.addRoundRect(inset, inset, w - inset, h - inset, Math.max(0f, r - inset), Math.max(0f, r - inset), Path.Direction.CW);
        nodesStale = true;
        invalidate();
    }

    private final Runnable invalidator = this::invalidate;

    private final Paint portalBlurPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final int[] screenLoc = new int[2];
    private final android.graphics.Rect portalDst = new android.graphics.Rect();
    private android.graphics.drawable.Drawable portalTickListener;

    private void ensurePortalLiveListener() {
        if (portalTickListener != null || PortalBackgroundHelper.isRobolectric()) return;
        android.app.Activity act = PortalBackgroundHelper.activityOf(getContext());
        PortalShaderView psv = PortalBackgroundHelper.getShaderView(act);
        if (psv == null) return;
        portalTickListener = new android.graphics.drawable.Drawable() {
            @Override public void draw(Canvas canvas) { }
            @Override public void setAlpha(int alpha) { }
            @Override public void setColorFilter(android.graphics.ColorFilter cf) { }
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
            @Override public void invalidateSelf() { GlassSurface.this.postInvalidateOnAnimation(); }
        };
        psv.registerFrostedDrawable(portalTickListener);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (spec == null || getWidth() == 0) return;
        drawShadow(canvas);
        if (isFocused() || focusShown) focusRing().drawHalo(canvas, 0, 0, getWidth(), getHeight(), cornerRadius());
        drawBack(canvas);
        canvas.drawPath(shape, fillPaint);
        // Flat uniform One UI card outline (no specular sheen or lens highlight)
        if (spec.rimWidth > 0f) {
            rimPaint.setShader(null);
            rimPaint.setColor(AppThemePalette.getCurrent(getContext()).getCardRimColor());
            rimPaint.setStrokeWidth(spec.rimWidth);
            canvas.drawPath(rimShape, rimPaint);
        }
    }

    @Override
    public void onDrawForeground(Canvas canvas) {
        super.onDrawForeground(canvas);
        if (isFocused() || focusShown) focusRing().drawRing(canvas, 0, 0, getWidth(), getHeight(), cornerRadius());
    }

    private boolean focusShown;

    /** The ring for a surface whose focus is on what it holds, such as a text field, which has no ring of its own. */
    public void setFocusShown(boolean shown) {
        if (focusShown == shown) return;
        focusShown = shown;
        invalidate();
    }

    private FocusRing ring;

    private FocusRing focusRing() {
        if (ring == null) ring = new FocusRing(this, false);
        return ring;
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, android.graphics.Rect previously) {
        super.onFocusChanged(gainFocus, direction, previously);
        retarget();
        invalidate();
    }

    @Override
    public void setPressed(boolean pressed) {
        boolean changed = pressed != isPressed();
        super.setPressed(pressed);
        if (changed && isClickable()) retarget();
    }

    private void retarget() {
        float focus = androidx.core.content.res.ResourcesCompat.getFloat(getResources(), R.dimen.nj_focus_scale_control);
        Springs.scale(this, isPressed() ? 0.97f : isFocused() ? focus : 1f, isPressed() ? Springs.Kind.PRESS : Springs.Kind.FOCUS);
    }

    private void drawShadow(Canvas canvas) {
        if (spec.shadowBlur <= 0f) return;
        GlassShadow shadow = GlassShadow.get(cornerRadius(), spec.shadowBlur, invalidator);
        if (shadow == null) return;
        canvas.save();
        // The surface is see-through: its own shadow must not show inside it.
        canvas.clipPath(shape, Region.Op.DIFFERENCE);
        shadow.draw(canvas, 0, spec.shadowY, getWidth(), getHeight() + spec.shadowY, shadowPaint);
        canvas.restore();
    }

    private void drawBack(Canvas canvas) {
        if (tier == GlassTier.SOLID) return;
        // Primary path on API 31+: use GlassHost's full-content RenderNode blur so scrolling app/PC/settings
        // cards AND the animated starfield behind floating pills all appear live in the blur.
        if (host != null && host.isLive()
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && canvas.isHardwareAccelerated()) {
            drawLive(canvas);
            return;
        }
        // Fallback path (API < 31 or BAKED tier): sample PortalShaderView's live blurred starfield bitmap.
        if (AppThemePalette.isLiveBlurEffective(getContext()) && !PortalBackgroundHelper.isRobolectric()) {
            android.app.Activity act = PortalBackgroundHelper.activityOf(getContext());
            PortalShaderView psv = PortalBackgroundHelper.getShaderView(act);
            if (psv != null) {
                ensurePortalLiveListener();
                android.graphics.Bitmap bmp = psv.getBlurredBitmap();
                if (bmp != null && !bmp.isRecycled()) {
                    getLocationOnScreen(screenLoc);
                    int screenW = getResources().getDisplayMetrics().widthPixels;
                    int screenH = getResources().getDisplayMetrics().heightPixels;
                    if (screenW > 0 && screenH > 0) {
                        int satPct = com.limelight.preferences.PreferenceConfiguration
                                .readPreferences(getContext()).blurSaturationPercent;
                        android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
                        cm.setSaturation(Math.max(0f, Math.min(3f, satPct / 100f)));
                        portalBlurPaint.setColorFilter(new ColorMatrixColorFilter(cm));
                        canvas.save();
                        canvas.clipPath(shape);
                        portalDst.set(-screenLoc[0], -screenLoc[1], screenW - screenLoc[0], screenH - screenLoc[1]);
                        canvas.drawBitmap(bmp, null, portalDst, portalBlurPaint);
                        canvas.restore();
                        return;
                    }
                }
            }
        }
        if (host == null) return;
        if (tier == GlassTier.BAKED) {
            canvas.save();
            canvas.clipPath(shape);
            host.drawBaked(canvas, atX, atY, getWidth(), getHeight(), bakedPaint);
            canvas.restore();
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private void drawLive(Canvas canvas) {
        RenderNode blur = host.blurNode(spec);
        if (blur == null) {
            post(invalidator);
            return;
        }
        if (nodesStale || glassNode == null) {
            recordGlass(blur);
            nodesStale = false;
        }
        canvas.drawRenderNode((RenderNode) glassNode);
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private void recordGlass(RenderNode blur) {
        RenderNode node = glassNode == null ? new RenderNode("glass") : (RenderNode) glassNode;
        glassNode = node;
        clip(node);
        RecordingCanvas recording = node.beginRecording(getWidth(), getHeight());
        recording.translate(-atX, -atY);
        recording.scale(BLUR_SCALE, BLUR_SCALE);
        recording.drawRenderNode(blur);
        node.endRecording();
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private void clip(RenderNode node) {
        node.setPosition(0, 0, getWidth(), getHeight());
        Outline outline = new Outline();
        outline.setRoundRect(0, 0, getWidth(), getHeight(), cornerRadius());
        node.setOutline(outline);
        node.setClipToOutline(true);
    }
}
