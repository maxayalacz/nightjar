package com.limelight;

import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.limelight.binding.input.HeldInputState;
import com.limelight.binding.input.pad.ModernController;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.NightjarActivity;
import com.limelight.utils.UiHelper;

/**
 * Standalone fullscreen editor for customizing Nightjar's {@link ModernController} layout
 * directly from Settings without starting an active stream.
 * Renders over the live {@link com.limelight.ui.PortalShaderView} starfield with unified
 * One UI frosted glass and {@link com.limelight.ui.AppThemePalette} colors.
 */
public class NightjarOscEditorActivity extends NightjarActivity {

    private FrameLayout editorCanvas;
    private ModernController modernController;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiHelper.setLocale(this);

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        setContentView(R.layout.activity_osc_layout_editor);
        UiHelper.applyMenuImmersiveMode(this);

        editorCanvas = findViewById(R.id.oscEditorCanvas);
        PreferenceConfiguration prefs = PreferenceConfiguration.readPreferences(this);

        modernController = new ModernController(
                this,
                editorCanvas,
                new HeldInputState.Sink() {
                    @Override public void key(int key, boolean down) { }
                    @Override public void mouse(int button, boolean down) { }
                    @Override public void gamepad(int buttons, short leftX, short leftY,
                                                  short rightX, short rightY,
                                                  byte leftTrigger, byte rightTrigger) { }
                },
                prefs,
                null);
        modernController.setOnEditorClosedListener(this::finish);
        modernController.refreshLayout();

        editorCanvas.post(this::mountEditor);
    }

    private void mountEditor() {
        if (isFinishing() || modernController == null || editorCanvas == null) {
            return;
        }
        if (!modernController.isEditing()) {
            modernController.startEditing();
        }
    }

    @Override
    public void onBackPressed() {
        if (modernController != null && modernController.isEditing()) {
            modernController.finishEditing(false);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        UiHelper.applyMenuImmersiveMode(this);
        if (modernController != null) {
            modernController.refreshLiveBlurPreferences();
        }
    }

    @Override
    protected void onDestroy() {
        if (modernController != null) {
            modernController.setOnEditorClosedListener(null);
            modernController.destroy();
            modernController = null;
        }
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (modernController != null) {
            modernController.onOrientationChanged();
        }
    }
}
