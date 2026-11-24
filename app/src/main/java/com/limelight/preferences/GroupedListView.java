package com.limelight.preferences;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.util.TypedValue;
import android.view.View;
import android.widget.ListView;

import com.google.android.material.color.MaterialColors;
import com.limelight.R;
import com.limelight.ui.NightSkyView;

/**
 * A preference list whose rows stand in groups: one flat tint with a rim behind each run of rows, a hairline between rows. A
 * flat tint on the sky, never glass (no nested blur). Headings and spacers end a group. One draw per group, no layer.
 */
public final class GroupedListView extends ListView {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hairline = new Paint();
    private final RectF box = new RectF();
    private final float radius;
    private final float inset;

    GroupedListView(Context context) {
        super(context);
        setId(android.R.id.list);
        radius = getResources().getDimension(R.dimen.nj_radius_group);
        inset = getResources().getDimension(R.dimen.settings_row_inset);
        fill.setColor(NightSkyView.groupFill(context));
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1, getResources().getDisplayMetrics()));
        rim.setColor(MaterialColors.getColor(this, R.attr.glassGroupRim));
        hairline.setColor(NightSkyView.separator(context));
        // Scrolled, the rows fade out at the top as they do into the page's foot, not cut off under the title (#61).
        setVerticalFadingEdgeEnabled(true);
        setFadingEdgeLength(getResources().getDimensionPixelSize(R.dimen.settings_top_fade));
    }

    /** The bottom is the page's: its foot fade on a phone, the screen's edge elsewhere. */
    @Override
    protected float getBottomFadingEdgeStrength() {
        return 0f;
    }

    /** The group draws its own hairlines; PreferenceFragment sets the theme's full-width divider after onCreateView. */
    @Override
    public void setDivider(android.graphics.drawable.Drawable divider) {
        super.setDivider(null);
    }

    /** Puts a grouped list where a preference fragment's list is, before the fragment binds it. */
    public static ListView adopt(View root) {
        ListView old = root.findViewById(android.R.id.list);
        if (old == null || old instanceof GroupedListView) return old;
        android.view.ViewGroup parent = (android.view.ViewGroup) old.getParent();
        int index = parent.indexOfChild(old);
        GroupedListView list = new GroupedListView(old.getContext());
        list.setVerticalScrollBarEnabled(false);
        list.setOverScrollMode(OVER_SCROLL_NEVER);
        parent.removeViewAt(index);
        parent.addView(list, index, old.getLayoutParams());
        return list;
    }

    /** Whether the row at this position stands in a group. */
    private boolean grouped(int position) {
        if (position < 0 || position >= getCount()) return false;
        Object item = getItemAtPosition(position);
        return item instanceof Preference && !(item instanceof PreferenceCategory);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        int first = getFirstVisiblePosition();
        int count = getChildCount();
        int start = -1;
        for (int i = 0; i <= count; i++) {
            boolean row = i < count && grouped(first + i);
            if (row && start < 0) start = i;
            if (!row && start >= 0) {
                drawGroup(canvas, first, start, i - 1);
                start = -1;
            }
        }
        super.dispatchDraw(canvas);
    }

    private final Paint blurPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Path clipPath = new android.graphics.Path();
    private final int[] screenLoc = new int[2];
    private final android.graphics.Rect portalDst = new android.graphics.Rect();
    private android.graphics.drawable.Drawable portalTickListener;

    private void ensurePortalListener() {
        if (portalTickListener != null) return;
        android.app.Activity act = com.limelight.ui.PortalBackgroundHelper.activityOf(getContext());
        com.limelight.ui.PortalShaderView psv = com.limelight.ui.PortalBackgroundHelper.getShaderView(act);
        if (psv == null) return;
        portalTickListener = new android.graphics.drawable.Drawable() {
            @Override public void draw(Canvas canvas) { }
            @Override public void setAlpha(int alpha) { }
            @Override public void setColorFilter(android.graphics.ColorFilter cf) { }
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
            @Override public void invalidateSelf() { GroupedListView.this.postInvalidateOnAnimation(); }
        };
        psv.registerFrostedDrawable(portalTickListener);
    }

    private void drawGroup(Canvas canvas, int first, int from, int to) {
        Context ctx = getContext();
        com.limelight.ui.AppThemePalette palette = com.limelight.ui.AppThemePalette.getCurrent(ctx);
        fill.setColor(palette.getFrostedCardTint(0x52121826));
        float outlineScale = Math.max(0f, PreferenceConfiguration.readPreferences(ctx).cardOutlineThicknessPercent / 100f);
        rim.setStrokeWidth(outlineScale <= 0f ? 0f
                : Math.max(1f, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, getResources().getDisplayMetrics()) * outlineScale));
        rim.setColor(palette.getCardRimColor());
        hairline.setColor((0x38 << 24) | (palette.cardOutline & 0x00FFFFFF));

        View top = getChildAt(from);
        View bottom = getChildAt(to);
        float y0 = grouped(first + from - 1) ? Math.min(top.getTop(), 0) - radius * 2 : top.getTop();
        float y1 = grouped(first + to + 1) ? Math.max(bottom.getBottom(), getHeight()) + radius * 2 : bottom.getBottom();
        box.set(top.getLeft(), y0, top.getRight(), y1);

        if (com.limelight.ui.AppThemePalette.isLiveBlurEffective(ctx)) {
            android.app.Activity act = com.limelight.ui.PortalBackgroundHelper.activityOf(ctx);
            com.limelight.ui.PortalShaderView psv = com.limelight.ui.PortalBackgroundHelper.getShaderView(act);
            if (psv != null) {
                ensurePortalListener();
                android.graphics.Bitmap bmp = psv.getBlurredBitmap();
                if (bmp != null && !bmp.isRecycled()) {
                    getLocationOnScreen(screenLoc);
                    int screenW = getResources().getDisplayMetrics().widthPixels;
                    int screenH = getResources().getDisplayMetrics().heightPixels;
                    if (screenW > 0 && screenH > 0) {
                        int satPct = PreferenceConfiguration.readPreferences(ctx).blurSaturationPercent;
                        android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
                        cm.setSaturation(Math.max(0f, Math.min(3f, satPct / 100f)));
                        blurPaint.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
                        clipPath.reset();
                        clipPath.addRoundRect(box, radius, radius, android.graphics.Path.Direction.CW);
                        canvas.save();
                        canvas.clipPath(clipPath);
                        portalDst.set(-screenLoc[0], -screenLoc[1], screenW - screenLoc[0], screenH - screenLoc[1]);
                        canvas.drawBitmap(bmp, null, portalDst, blurPaint);
                        canvas.restore();
                    }
                }
            }
        }

        canvas.drawRoundRect(box, radius, radius, fill);
        float half = rim.getStrokeWidth() / 2f;
        if (half > 0f) {
            box.inset(half, half);
            canvas.drawRoundRect(box, Math.max(0f, radius - half), Math.max(0f, radius - half), rim);
        }
        float divH = Math.max(1f, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 0.75f, getResources().getDisplayMetrics()));
        for (int i = from + 1; i <= to; i++) {
            View row = getChildAt(i);
            canvas.drawRect(row.getLeft() + inset, row.getTop(), row.getRight() - inset, row.getTop() + divH, hairline);
        }
    }
}
