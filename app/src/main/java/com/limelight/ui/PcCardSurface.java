package com.limelight.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.limelight.R;
import com.limelight.preferences.PreferenceConfiguration;

import java.lang.ref.WeakReference;

/**
 * Self-blurring One UI frosted glass surface for landscape/handheld/TV PC cards ({@code pc_grid_card.xml}),
 * matching {@link PcRow} and {@link CoverCard} with live {@link PortalShaderView} backdrop sampling and
 * {@link AppThemePalette} colors.
 */
public class PcCardSurface extends FrameLayout {
    private final Paint blurPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clipPath = new Path();
    private final RectF bounds = new RectF();
    private final Rect portalDst = new Rect();
    private final int[] screenLoc = new int[2];
    private final float cornerRadius;
    private WeakReference<PortalShaderView> portalRef;
    private int cachedSatPct = -1;

    private final Drawable blurTick = new Drawable() {
        @Override public void draw(@NonNull Canvas canvas) { }
        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(@Nullable ColorFilter colorFilter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public void invalidateSelf() {
            if (isAttachedToWindow()) {
                PcCardSurface.this.postInvalidateOnAnimation();
            }
        }
    };

    public PcCardSurface(@NonNull Context context) {
        this(context, null);
    }

    public PcCardSurface(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        cornerRadius = context.getResources().getDimension(R.dimen.nj_radius_panel);
        fillPaint.setColor(0x52121826);
        pressPaint.setColor(0x22FFFFFF);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refreshTheme(null);
    }

    @Override
    public void setPressed(boolean pressed) {
        boolean changed = pressed != isPressed();
        super.setPressed(pressed);
        if (changed) invalidate();
    }

    public void refreshTheme(@Nullable PortalShaderView explicitPortal) {
        Context ctx = getContext();
        AppThemePalette palette = AppThemePalette.getCurrent(ctx);
        PreferenceConfiguration cfg = PreferenceConfiguration.readPreferences(ctx);

        fillPaint.setColor(palette.getFrostedCardTint(0x52121826));
        pressPaint.setColor((0x2C << 24) | (palette.accent & 0x00FFFFFF));

        if (cachedSatPct != cfg.blurSaturationPercent) {
            cachedSatPct = cfg.blurSaturationPercent;
            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(Math.max(0f, Math.min(3f, cachedSatPct / 100f)));
            blurPaint.setColorFilter(new ColorMatrixColorFilter(cm));
        }

        TextView title = findViewById(R.id.grid_text);
        if (title != null) title.setTextColor(palette.getPrimaryTextColor());
        TextView status = findViewById(R.id.grid_status);
        if (status != null) status.setTextColor(palette.getSecondaryTextColor());
        TextView update = findViewById(R.id.grid_update);
        if (update != null) update.setTextColor(palette.accent);
        ImageButton more = findViewById(R.id.grid_more);
        if (more != null) more.setImageTintList(ColorStateList.valueOf(palette.getSecondaryTextColor()));
        View resume = findViewById(R.id.grid_resume);
        if (resume instanceof GlassButton) ((GlassButton) resume).refreshTheme();
        View action = findViewById(R.id.grid_action);
        if (action instanceof GlassButton) ((GlassButton) action).refreshTheme();

        if (!PortalBackgroundHelper.isRobolectric()) {
            PortalShaderView psv = explicitPortal != null
                    ? explicitPortal
                    : PortalBackgroundHelper.getShaderView(PortalBackgroundHelper.activityOf(ctx));
            if (psv != null) {
                portalRef = new WeakReference<>(psv);
                psv.registerFrostedDrawable(blurTick);
            }
        }
        invalidate();
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w > 0 && h > 0) {
            bounds.set(0, 0, w, h);
            clipPath.rewind();
            clipPath.addRoundRect(bounds, cornerRadius, cornerRadius, Path.Direction.CW);

            if (!PortalBackgroundHelper.isRobolectric() && AppThemePalette.isLiveBlurEffective(getContext())) {
                PortalShaderView psv = portalRef != null ? portalRef.get() : null;
                if (psv == null) {
                    psv = PortalBackgroundHelper.getShaderView(PortalBackgroundHelper.activityOf(getContext()));
                    if (psv != null) {
                        portalRef = new WeakReference<>(psv);
                        psv.registerFrostedDrawable(blurTick);
                    }
                }
                if (psv != null) {
                    Bitmap bmp = psv.getBlurredBitmap();
                    if (bmp != null && !bmp.isRecycled()) {
                        getLocationOnScreen(screenLoc);
                        int screenW = getResources().getDisplayMetrics().widthPixels;
                        int screenH = getResources().getDisplayMetrics().heightPixels;
                        if (screenW > 0 && screenH > 0) {
                            canvas.save();
                            canvas.clipPath(clipPath);
                            portalDst.set(-screenLoc[0], -screenLoc[1], screenW - screenLoc[0], screenH - screenLoc[1]);
                            canvas.drawBitmap(bmp, null, portalDst, blurPaint);
                            canvas.restore();
                        }
                    }
                }
            }

            canvas.drawPath(clipPath, fillPaint);
            if (isPressed()) {
                canvas.drawPath(clipPath, pressPaint);
            }
        }
        super.dispatchDraw(canvas);
    }
}
