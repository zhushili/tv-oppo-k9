package com.gathin.tvhome;

import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.TextView;

/** The tvOS-style top shelf: focused dock title plus an ambient glow sampled from the artwork. */
final class HeroController {
    private final TextView name;
    private final View glowView;
    private final GradientDrawable glow;
    private final float density;
    private boolean shown;
    private int ambientColor;

    HeroController(TextView name, View glowView, float density) {
        this.name = name;
        this.glowView = glowView;
        this.density = density;
        this.glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setGradientCenter(0.32f, 0.42f);
        glowView.setBackground(glow);
        glowView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                glow.setGradientRadius((r - l) * 0.62f));
    }

    void show(String label, int color, float dockTopInRoot) {
        float y = dockTopInRoot - 14 * density - 26 * density - name.getHeight();
        boolean changed = !label.contentEquals(name.getText());
        if (!shown) {
            name.animate().cancel();
            glowView.animate().cancel();
            name.setText(label);
            name.setY(y);
            name.setTranslationX(0f);
            name.animate().alpha(1f).setDuration(260).start();
            setAmbient(color);
            glowView.animate().alpha(1f).setDuration(600).start();
            shown = true;
        } else if (changed) {
            name.animate().cancel();
            name.animate().alpha(0f).translationX(-12 * density).setDuration(120).withEndAction(() -> {
                name.setText(label);
                name.setY(y);
                name.setTranslationX(12 * density);
                name.animate().alpha(1f).translationX(0f).setDuration(200).start();
            }).start();
            if (color != ambientColor) {
                glowView.animate().cancel();
                glowView.animate().alpha(0.35f).setDuration(200).withEndAction(() -> {
                    setAmbient(color);
                    glowView.animate().alpha(1f).setDuration(500).start();
                }).start();
            }
        } else {
            name.setY(y);
        }
    }

    void hide() {
        if (!shown) return;
        shown = false;
        name.animate().cancel();
        glowView.animate().cancel();
        name.animate().alpha(0f).setDuration(160).start();
        glowView.animate().alpha(0f).setDuration(300).start();
    }

    private void setAmbient(int color) {
        ambientColor = color;
        int c = color & 0x00FFFFFF;
        glow.setColors(new int[]{0x5C000000 | c, 0x26000000 | c, 0x00000000 | c});
    }
}
