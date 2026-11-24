package com.limelight.ui;

import android.content.Context;
import android.content.DialogInterface;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ListView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.limelight.R;

/**
 * The app's dialogs: the glass panel, one moon action, and the same Back/B/Escape navigation as the action sheets. All of them
 * come from here, so they are one look on every form.
 */
public final class NavigationDialogBuilder extends MaterialAlertDialogBuilder {
    private DialogInterface.OnKeyListener keyListener;
    private boolean startOnCancel;
    private boolean ownSurface;
    private final boolean overStream;

    public NavigationDialogBuilder(Context context) {
        super(context);
        // Over a stream, the stream menu's glass: StreamGlass.show puts a blur under it where the device affords one.
        overStream = StreamGlass.overStream(getContext());
        setBackground(overStream ? GlassPanelDrawable.menu(getContext(), false) : GlassPanelDrawable.dialog(getContext()));
    }

    /** For a confirmation that ends or loses something: a pad's or a remote's first press is Cancel, not the moon. */
    public NavigationDialogBuilder startOnCancel() {
        startOnCancel = true;
        return this;
    }

    /**
     * For a dialog that is a task of its own (a pairing PIN): it stands on the sky instead of over the page, so its panel never
     * covers the page's heading or a card's edge. Over a stream it stays as any dialog there.
     */
    public NavigationDialogBuilder ownSurface() {
        ownSurface = true;
        return this;
    }

    @Override
    public MaterialAlertDialogBuilder setOnKeyListener(DialogInterface.OnKeyListener listener) {
        keyListener = listener;
        return super.setOnKeyListener(listener);
    }

    @Override
    public AlertDialog create() {
        AlertDialog dialog = super.create();
        // The platform's selector is a grey block, which is no focus language of ours.
        ListView list = dialog.getListView();
        if (list != null) {
            if (PortalBackgroundHelper.isRobolectric()) {
                list.setSelector(R.drawable.dialog_row_focus);
            } else {
                list.setSelector(com.limelight.utils.UiHelper.createThemedRowSelector(
                        getContext(), AppThemePalette.getCurrent(getContext())));
            }
        }
        boolean onSky = ownSurface && !overStream;
        if (dialog.getWindow() != null) {
            if (overStream || onSky) {
                dialog.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            } else {
                dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                dialog.getWindow().setDimAmount(0.35f);
            }
        }
        if (onSky) DialogSky.under(dialog);
        // Builds the views now: the buttons exist, and their bar can be laid out for capsules.
        dialog.create();
        if (!PortalBackgroundHelper.isRobolectric()) {
            com.limelight.utils.UiHelper.styleDialogSquircle(dialog);
            styleButtons(dialog);
            if (dialog.getWindow() != null) {
                View decor = dialog.getWindow().getDecorView();
                decor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(View v) {
                        v.removeOnAttachStateChangeListener(this);
                        com.limelight.utils.UiHelper.styleDialogSquircle(dialog);
                        styleButtons(dialog);
                    }

                    @Override
                    public void onViewDetachedFromWindow(View v) { }
                });
            }
        } else {
            styleButtons(dialog);
        }
        DialogInterface.OnKeyListener custom = keyListener;
        DpadEchoFilter dpadEchoes = new DpadEchoFilter();
        dialog.setOnKeyListener((target, key, event) -> {
            if (dpadEchoes.drop(event)) return true;
            if (custom != null && custom.onKey(target, key, event)) return true;
            if (UiNavigation.swallowsPadY(key, dialog.getCurrentFocus())) return true;
            if (!event.hasNoModifiers() || (key != KeyEvent.KEYCODE_BUTTON_B && key != KeyEvent.KEYCODE_ESCAPE)) return false;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) dialog.onBackPressed();
            return true;
        });
        return dialog;
    }

    private void styleButtons(AlertDialog dialog) {
        Context ctx = getContext();
        AppThemePalette palette = AppThemePalette.getCurrent(ctx);
        // Style dialog text elements with active theme palette
        android.widget.TextView titleTv = dialog.findViewById(androidx.appcompat.R.id.alertTitle);
        if (titleTv != null) titleTv.setTextColor(palette.getPrimaryTextColor());
        android.widget.TextView msgTv = dialog.findViewById(android.R.id.message);
        if (msgTv != null) msgTv.setTextColor(palette.getSecondaryTextColor());

        Button positive = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        Button negative = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
        Button neutral = dialog.getButton(DialogInterface.BUTTON_NEUTRAL);

        float radius = com.limelight.utils.UiHelper.dpToPx(ctx, 20f);
        float outlineScale = Math.max(0f, com.limelight.preferences.PreferenceConfiguration
                .readPreferences(ctx).cardOutlineThicknessPercent / 100f);
        int strokePx = outlineScale <= 0f ? 0 : Math.max(1, Math.round(com.limelight.utils.UiHelper.dpToPx(ctx, 1f) * outlineScale));

        if (positive != null) {
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[] {palette.highlight, palette.accent});
            bg.setCornerRadius(radius);
            positive.setBackgroundTintList(null);
            positive.setBackground(bg);
            int onAccent = androidx.core.graphics.ColorUtils.calculateLuminance(palette.accent) > 0.45
                    ? 0xFF0B0E14 : 0xFFFFFFFF;
            positive.setTextColor(onAccent);
        }
        for (Button secondary : new Button[] {negative, neutral}) {
            if (secondary == null) continue;
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(radius);
            bg.setColor(palette.getFrostedCardTint(0x52121826));
            if (strokePx > 0) bg.setStroke(strokePx, palette.getCardRimColor());
            secondary.setBackgroundTintList(null);
            secondary.setBackground(bg);
            secondary.setTextColor(palette.getPrimaryTextColor());
        }

        if (positive == null || !(positive.getParent() instanceof ViewGroup)) return;
        android.content.res.Resources res = ctx.getResources();
        int edge = res.getDimensionPixelSize(R.dimen.space_l);
        ViewGroup bar = (ViewGroup) positive.getParent();
        bar.setPaddingRelative(edge, res.getDimensionPixelSize(R.dimen.space_m), edge, edge);
        if (bar.getParent() instanceof android.widget.ScrollView) {
            View scroller = (View) bar.getParent();
            scroller.setFocusable(false);
            if (android.os.Build.VERSION.SDK_INT >= 26) scroller.setDefaultFocusHighlightEnabled(false);
        }
        boolean cancelFirst = startOnCancel && negative != null && negative.getVisibility() == View.VISIBLE;
        if (android.os.Build.VERSION.SDK_INT >= 26 && dialog.getListView() == null) (cancelFirst ? negative : positive).setFocusedByDefault(true);
        bar.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> {
            if (!(bar instanceof android.widget.LinearLayout)) return;
            if (((android.widget.LinearLayout) bar).getOrientation() == android.widget.LinearLayout.VERTICAL) {
                bar.post(() -> stretchStacked(bar));
            } else if (rowWiderThanBar(bar)) {
                ((android.widget.LinearLayout) bar).setOrientation(android.widget.LinearLayout.VERTICAL);
                bar.post(() -> stretchStacked(bar));
            }
        });
    }

    /**
     * Whether the bar's visible buttons leave no room in its row, which would run the row past the panel: a dialog's
     * button scroller scrolls vertically only, so the last capsule would be cut on the right. A capsule the row
     * squeezed is clamped to the space left without a too-small state of its own (a TextView clamps itself that way),
     * so a row the buttons fill exactly counts too. The spacer and the other children take no width of their own.
     */
    static boolean rowWiderThanBar(ViewGroup bar) {
        int available = bar.getWidth() - bar.getPaddingLeft() - bar.getPaddingRight();
        if (available <= 0) return false;
        int needed = 0;
        for (int i = 0; i < bar.getChildCount(); i++) {
            View child = bar.getChildAt(i);
            if (!(child instanceof Button) || child.getVisibility() != View.VISIBLE) continue;
            if ((child.getMeasuredWidthAndState() & View.MEASURED_STATE_TOO_SMALL) != 0) return true;
            needed += child.getMeasuredWidth();
            ViewGroup.LayoutParams params = child.getLayoutParams();
            if (params instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
                needed += margins.getMarginStart() + margins.getMarginEnd();
            }
        }
        return needed >= available;
    }

    private void stretchStacked(ViewGroup bar) {
        int gap = getContext().getResources().getDimensionPixelSize(R.dimen.space_s);
        boolean first = true;
        for (int i = 0; i < bar.getChildCount(); i++) {
            View child = bar.getChildAt(i);
            if (!(child instanceof Button) || child.getVisibility() != View.VISIBLE) continue;
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) child.getLayoutParams();
            int top = first ? 0 : gap;
            first = false;
            if (params.width == ViewGroup.LayoutParams.MATCH_PARENT && params.topMargin == top && params.getMarginStart() == 0) continue;
            params.width = ViewGroup.LayoutParams.MATCH_PARENT;
            params.topMargin = top;
            params.setMarginStart(0);
            child.setLayoutParams(params);
        }
    }
}
