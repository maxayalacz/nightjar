package com.limelight.ui;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.AbsListView;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import com.google.android.material.color.MaterialColors;
import com.limelight.R;
import com.limelight.preferences.PreferenceConfiguration;

import java.lang.ref.WeakReference;

/**
 * A PC's row in a phone's grouped list. The list has one spacing, so a row draws its share of the group's shape: rounded where
 * the group starts and ends, a line where another row follows, and the gap to the next group under it. Focus is the glass ring,
 * drawn around the row's own shape: the grid's selector could only frame the row and the gap together.
 */
public class PcRow extends FrameLayout {
    private final Paint blurPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint();
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint press = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path();
    private final Path rimShape = new Path();
    private final RectF rect = new RectF();
    private final Rect srcRect = new Rect();
    private final int[] rowLoc = new int[2];
    private final int[] portalLoc = new int[2];
    private final float[] corners = new float[8];
    private final FocusRing ring;
    private final float groupRadius;
    private final float rowRadius;
    private final int gap;
    private final boolean highContrast;
    private boolean startsGroup = true;
    private boolean endsGroup = true;
    private boolean gapBelow;
    private float lineInset;
    private int shapedW;
    private int shapedH;
    private WeakReference<PortalShaderView> portalRef;
    private int cachedSatPct = -1;
    private float rimStrokeWidth;

    private final Drawable blurTick = new Drawable() {
        @Override public void draw(@NonNull Canvas canvas) { }
        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(@Nullable ColorFilter colorFilter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public void invalidateSelf() {
            if (isAttachedToWindow()) {
                PcRow.this.invalidate();
            }
        }
    };

    public PcRow(Context context) {
        this(context, null);
    }

    public PcRow(Context context, AttributeSet attrs) {
        super(context, attrs);
        Resources res = context.getResources();
        float density = res.getDisplayMetrics().density;
        highContrast = AppTheme.isHighContrast(context);
        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);
        groupRadius = res.getDimension(R.dimen.nj_radius_panel);
        rowRadius = 14 * density;
        gap = res.getDimensionPixelSize(R.dimen.pc_group_gap);
        fill.setColor(NightSkyView.groupFill(context));
        line.setColor(NightSkyView.separator(context));
        line.setStrokeWidth(Math.max(1f, density * .5f));
        rim.setStyle(Paint.Style.STROKE);
        rimStrokeWidth = res.getDimension(R.dimen.nj_glass_chrome_solid_rim_width);
        rim.setStrokeWidth(rimStrokeWidth);
        rim.setColor(ResourcesCompat.getColor(res, R.color.nj_hc_color_rim, null));
        press.setColor(MaterialColors.getColor(context, R.attr.glassControlFill, 0x22FFFFFF));
        ring = new FocusRing(this, false);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        View cover = findViewById(R.id.grid_cover_frame);
        if (cover == null) return;
        float radius = 14 * getResources().getDisplayMetrics().density;
        cover.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        cover.setClipToOutline(true);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refreshTheme(null);
    }

    /** Re-applies active AppThemePalette colors and hooks live PortalShaderView frosted blur immediately. */
    public void refreshTheme(@Nullable PortalShaderView explicitPortal) {
        Context ctx = getContext();
        AppThemePalette palette = AppThemePalette.getCurrent(ctx);
        PreferenceConfiguration cfg = PreferenceConfiguration.readPreferences(ctx);
        float density = ctx.getResources().getDisplayMetrics().density;

        fill.setColor(palette.getFrostedCardTint(0x52121826));
        line.setColor((0x2C << 24) | (palette.cardOutline & 0x00FFFFFF));
        press.setColor((0x2C << 24) | (palette.accent & 0x00FFFFFF));

        if (highContrast) {
            rimStrokeWidth = ctx.getResources().getDimension(R.dimen.nj_glass_chrome_solid_rim_width);
            rim.setColor(ResourcesCompat.getColor(ctx.getResources(), R.color.nj_hc_color_rim, null));
        } else {
            float outlineScale = Math.max(0f, cfg.cardOutlineThicknessPercent / 100f);
            rimStrokeWidth = outlineScale <= 0f ? 0f : Math.max(1f, density * outlineScale);
            rim.setColor(palette.getCardRimColor());
        }
        rim.setStrokeWidth(rimStrokeWidth);
        ring.refreshTheme(ctx);

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
        shapedW = 0;
        invalidate();
    }

    /**
     * Where this row stands in its group.
     *
     * @param lineInset where the line above this row starts, from the row's start edge
     */
    public void setPlace(boolean starts, boolean ends, boolean moreBelow, float lineInset) {
        boolean belowChanged = (ends && moreBelow) != gapBelow;
        startsGroup = starts;
        endsGroup = ends;
        gapBelow = ends && moreBelow;
        this.lineInset = lineInset;
        shapedW = 0;
        if (belowChanged) setPadding(getPaddingLeft(), getPaddingTop(), getPaddingRight(), gapBelow ? gap : 0);
        invalidate();
    }

    private int shapeBottom() {
        return getHeight() - (gapBelow ? gap : 0);
    }

    private void shape(int w, int h) {
        shapedW = w;
        shapedH = h;
        float top = startsGroup ? groupRadius : 0f;
        float bottom = endsGroup ? groupRadius : 0f;
        corners[0] = corners[1] = corners[2] = corners[3] = top;
        corners[4] = corners[5] = corners[6] = corners[7] = bottom;
        rect.set(0, 0, w, h);
        shape.rewind();
        shape.addRoundRect(rect, corners, Path.Direction.CW);
        if (rimStrokeWidth > 0.1f) {
            float half = rimStrokeWidth * 0.5f;
            rect.inset(half, half);
            rimShape.rewind();
            rimShape.addRoundRect(rect, corners, Path.Direction.CW);
            rect.set(0, 0, w, h);
        }
    }

    /** The grid keeps a selection while focus is elsewhere; the ring is only for what the keys would act on now. */
    private boolean showsRing() {
        if (!isSelected() && !isHovered()) return false;
        if (isHovered()) return true;
        return getParent() instanceof AbsListView && ((AbsListView) getParent()).hasFocus();
    }

    private float ringRadius() {
        return startsGroup && endsGroup ? groupRadius : rowRadius;
    }

    @Override
    public void setSelected(boolean selected) {
        boolean changed = selected != isSelected();
        super.setSelected(selected);
        if (changed) {
            // Its ring lies over the rows on either side.
            setTranslationZ(selected ? getResources().getDisplayMetrics().density : 0f);
            invalidate();
        }
    }

    @Override
    public void setPressed(boolean pressed) {
        boolean changed = pressed != isPressed();
        super.setPressed(pressed);
        if (changed) invalidate();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        int w = getWidth();
        int h = shapeBottom();
        if (w != shapedW || h != shapedH) shape(w, h);
        boolean ringed = showsRing();
        if (ringed) {
            // The row's fill is see-through: the halo stays outside its shape.
            int save = canvas.save();
            canvas.clipPath(shape, android.graphics.Region.Op.DIFFERENCE);
            ring.drawHalo(canvas, 0, 0, w, h, ringRadius());
            canvas.restoreToCount(save);
        }

        // Sample live PortalShaderView frosted blur directly behind this PC row
        PortalShaderView psv = portalRef != null ? portalRef.get() : null;
        if (psv == null && !PortalBackgroundHelper.isRobolectric()) {
            psv = PortalBackgroundHelper.getShaderView(PortalBackgroundHelper.activityOf(getContext()));
            if (psv != null) {
                portalRef = new WeakReference<>(psv);
                psv.registerFrostedDrawable(blurTick);
            }
        }
        boolean batterySaverSolid = psv != null
                ? psv.isBatterySaverEffective()
                : PreferenceConfiguration.isUiBatterySaverEffective(getContext());
        if (!batterySaverSolid && psv != null && psv.getWidth() > 0 && psv.getHeight() > 0 && w > 0 && h > 0) {
            Bitmap blurred = psv.getBlurredBitmap();
            if (blurred != null && !blurred.isRecycled()) {
                getLocationInWindow(rowLoc);
                psv.getLocationInWindow(portalLoc);
                int relX = rowLoc[0] - portalLoc[0];
                int relY = rowLoc[1] - portalLoc[1];
                float sx = blurred.getWidth() / (float) psv.getWidth();
                float sy = blurred.getHeight() / (float) psv.getHeight();
                srcRect.set(
                        Math.max(0, Math.round(relX * sx)),
                        Math.max(0, Math.round(relY * sy)),
                        Math.min(blurred.getWidth(), Math.round((relX + w) * sx)),
                        Math.min(blurred.getHeight(), Math.round((relY + h) * sy)));
                if (!srcRect.isEmpty()) {
                    int saveBlur = canvas.save();
                    canvas.clipPath(shape);
                    rect.set(0, 0, w, h);
                    canvas.drawBitmap(blurred, srcRect, rect, blurPaint);
                    canvas.restoreToCount(saveBlur);
                }
            }
        }

        if (batterySaverSolid) {
            int saved = fill.getColor();
            fill.setColor(saved | 0xFF000000);
            canvas.drawPath(shape, fill);
            fill.setColor(saved);
        } else {
            canvas.drawPath(shape, fill);
        }
        if (rimStrokeWidth > 0.1f) {
            canvas.drawPath(rimShape, rim);
        }
        if (!startsGroup) {
            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            canvas.drawLine(rtl ? 0 : lineInset, 0, rtl ? w - lineInset : w, 0, line);
        }
        if (isPressed()) canvas.drawPath(shape, press);
        super.dispatchDraw(canvas);
        if (ringed) ring.drawRing(canvas, 0, 0, w, h, ringRadius());
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    /** For tests: whether the ring would draw now. */
    public boolean ringShown() {
        return showsRing();
    }

    /** For tests: the height of the row's shape, without the gap under it. */
    public int shapeHeight() {
        return shapeBottom();
    }
}
