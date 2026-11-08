/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package goodvin.locsync.shared;

import goodvin.locsync.logexporter.R;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;


/**
 * The circular power control at the heart of the redesign: three stacked layers (halo, ring, core)
 * plus a power glyph, one per {@link LinkState}. While {@link LinkState#WAITING} a single
 * {@link ValueAnimator} drives the halo + core "breathing"; the {@link LinkState#CONNECTED}
 * transition fades and rises the connected layers in over 350&nbsp;ms.
 *
 * Colors come from the shared Nocturne tokens; sizes/timings match the design handoff.
 */
public class PowerOrbView extends View {
    // Design sizes (dp), relative to the 264dp touch target.
    private static final float HALO_DP = 264f;
    private static final float RING_DP = 236f;
    private static final float CORE_DP = 176f;
    private static final float GLYPH_DP = 58f;

    private static final long BREATH_MS = 2200L;
    private static final long CONNECT_MS = 350L;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path glyph = new Path();

    private float density;
    private LinkState state = LinkState.STOPPED;

    // 0..1 breathing phase (waiting only) and 0..1 connect-in progress.
    private float breath = 0f;
    private float connect = 0f;
    private ValueAnimator breathAnimator;
    private ValueAnimator connectAnimator;
    private boolean shown;   // last onVisibilityAggregated: the breathing runs only while on screen

    // Built once per size (onSizeChanged); the animations vary them through paint alpha and scale.
    private RadialGradient haloWaiting, haloConnected, coreStopped, coreWaiting, coreConnected;

    // Token colors resolved once.
    private int cAccent, cAccent300, cAccent400, cAccent700, cAccent900, cNeutral500;

    public PowerOrbView(Context context) {
        super(context);
        init(context);
    }

    public PowerOrbView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public PowerOrbView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        density = context.getResources().getDisplayMetrics().density;
        cAccent = color(R.color.ls_accent);
        cAccent300 = color(R.color.ls_accent_300);
        cAccent400 = color(R.color.ls_accent_400);
        cAccent700 = color(R.color.ls_accent_700);
        cAccent900 = color(R.color.ls_accent_900);
        cNeutral500 = color(R.color.ls_neutral_500);
        stroke.setStyle(Paint.Style.STROKE);
        setContentDescription(context.getString(R.string.cd_power));
    }

    private int color(int res) {
        return getResources().getColor(res, null);
    }

    private float dp(float v) {
        return v * density;
    }

    public LinkState getState() {
        return state;
    }

    /** Switch state and start/stop the matching animations. Safe to call repeatedly. */
    public void setState(LinkState newState) {
        if (newState == state) {
            return;
        }
        LinkState old = state;
        state = newState;

        if (newState == LinkState.WAITING) {
            connect = 0f;
            startBreathing();
        } else {
            stopBreathing();
        }

        if (newState == LinkState.CONNECTED) {
            startConnectIn();
        } else {
            cancelConnectIn();
            connect = (newState == LinkState.CONNECTED) ? 1f : 0f;
        }

        if (newState != LinkState.WAITING && old == LinkState.WAITING) {
            breath = 0f;
        }
        invalidate();
    }

    private void startBreathing() {
        if (breathAnimator != null) {
            return;
        }
        breathAnimator = ValueAnimator.ofFloat(0f, 1f);
        breathAnimator.setDuration(BREATH_MS);
        breathAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        breathAnimator.setRepeatCount(ValueAnimator.INFINITE);
        breathAnimator.setRepeatMode(ValueAnimator.REVERSE);
        breathAnimator.addUpdateListener(a -> {
            breath = (float) a.getAnimatedValue();
            invalidate();
        });
        breathAnimator.start();
        if (!shown) breathAnimator.pause();
    }

    private void stopBreathing() {
        if (breathAnimator != null) {
            breathAnimator.cancel();
            breathAnimator = null;
        }
    }

    private void startConnectIn() {
        cancelConnectIn();
        connectAnimator = ValueAnimator.ofFloat(0f, 1f);
        connectAnimator.setDuration(CONNECT_MS);
        connectAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator());
        connectAnimator.addUpdateListener(a -> {
            connect = (float) a.getAnimatedValue();
            invalidate();
        });
        connectAnimator.start();
    }

    private void cancelConnectIn() {
        if (connectAnimator != null) {
            connectAnimator.cancel();
            connectAnimator = null;
        }
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        shown = isVisible;
        if (breathAnimator != null) {
            if (isVisible) breathAnimator.resume(); else breathAnimator.pause();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float cx = w / 2f;
        float cy = h / 2f;
        // Halo: accent → transparent; core: lit from above (#3F424D → #292B31, #8B7FD0 → #5D5294,
        // #D2CEFD → #B5ABFC(34%) → #9184D9).
        haloWaiting = halo(cx, cy, cAccent, 0.28f, 0.72f);
        haloConnected = halo(cx, cy, cAccent, 0.34f, 0.70f);
        coreStopped = core(cx, cy, new int[]{0xFF3F424D, 0xFF292B31}, new float[]{0f, 0.78f});
        coreWaiting = core(cx, cy, new int[]{0xFF8B7FD0, 0xFF5D5294}, new float[]{0f, 0.78f});
        coreConnected = core(cx, cy, new int[]{cAccent300, cAccent400, cAccent}, new float[]{0f, 0.34f, 1f});
    }

    private RadialGradient halo(float cx, float cy, int rgb, float centreAlpha, float transparentStop) {
        int centre = withAlpha(rgb, centreAlpha);
        return new RadialGradient(cx, cy, dp(HALO_DP) / 2f,
                new int[]{centre, centre, withAlpha(rgb, 0f)},
                new float[]{0f, Math.max(0f, transparentStop - 0.35f), transparentStop},
                Shader.TileMode.CLAMP);
    }

    private RadialGradient core(float cx, float cy, int[] colors, float[] stops) {
        float r = dp(CORE_DP) / 2f;
        float focusY = cy - r * 0.36f; // centre offset upward so it reads lit from above
        return new RadialGradient(cx, focusY, r, colors, stops, Shader.TileMode.CLAMP);
    }

    @Override
    protected void onDetachedFromWindow() {
        stopBreathing();
        cancelConnectIn();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (coreStopped == null) return;   // not laid out yet
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;

        switch (state) {
            case STOPPED -> drawStopped(canvas, cx, cy);
            case WAITING -> drawWaiting(canvas, cx, cy);
            case CONNECTED -> drawConnected(canvas, cx, cy);
        }
    }

    private void drawStopped(Canvas canvas, float cx, float cy) {
        // Ring: 9% white, 1px.
        stroke.setStrokeWidth(dp(1));
        stroke.setShader(null);
        stroke.setColor(Color.argb(0x17, 0xE9, 0xE9, 0xED));
        canvas.drawCircle(cx, cy, dp(RING_DP) / 2f, stroke);

        drawShaded(canvas, coreStopped, cx, cy, dp(CORE_DP) / 2f, 1f, 1f);
        drawTopHighlight(canvas, cx, cy, Color.argb(0x1A, 0xE9, 0xE9, 0xED), 1f);
        drawGlyph(canvas, cx, cy, cNeutral500, false, 1f);
    }

    private void drawWaiting(Canvas canvas, float cx, float cy) {
        // Halo: accent 28% → transparent at ~0.72 radius; opacity .35↔.75, scale .98↔1.06.
        float haloAlpha = lerp(0.35f, 0.75f, breath);
        float haloScale = lerp(0.98f, 1.06f, breath);
        drawShaded(canvas, haloWaiting, cx, cy, dp(HALO_DP) / 2f, haloAlpha / 0.75f, haloScale);

        // Ring: accent 35%.
        stroke.setStrokeWidth(dp(1));
        stroke.setShader(null);
        stroke.setColor(withAlpha(cAccent, 0.35f));
        canvas.drawCircle(cx, cy, dp(RING_DP) / 2f, stroke);

        // Core: #8B7FD0 → #5D5294, scale 1↔1.04, opacity .9↔1.
        float coreScale = lerp(1f, 1.04f, breath);
        float coreAlpha = lerp(0.9f, 1f, breath);
        drawShaded(canvas, coreWaiting, cx, cy, dp(CORE_DP) / 2f, coreAlpha, coreScale);
        drawTopHighlight(canvas, cx, cy, Color.argb(0x38, 0xF5, 0xF4, 0xFF), coreScale);
        drawGlyph(canvas, cx, cy, 0xFFE7E5FE, false, 1f);
    }

    private void drawConnected(Canvas canvas, float cx, float cy) {
        float a = connect;                 // 0..1 fade
        float dy = dp(6) * (1f - connect); // rise 6dp→0

        canvas.save();
        canvas.translate(0, dy);

        // Halo: accent 34% → transparent at 70%.
        drawShaded(canvas, haloConnected, cx, cy, dp(HALO_DP) / 2f, a, 1f);

        // Ring: accent 100%.
        stroke.setStrokeWidth(dp(1));
        stroke.setShader(null);
        stroke.setColor(withAlpha(cAccent, a));
        canvas.drawCircle(cx, cy, dp(RING_DP) / 2f, stroke);

        // Core: #D2CEFD → #B5ABFC(34%) → #9184D9.
        drawShaded(canvas, coreConnected, cx, cy, dp(CORE_DP) / 2f, a, 1f);
        drawTopHighlight(canvas, cx, cy, withAlpha(Color.WHITE, 0.66f * a), 1f);
        drawGlyph(canvas, cx, cy, cAccent900, true, a);

        canvas.restore();
    }

    // --- layer helpers ---

    /** A cached gradient circle, faded by paint alpha and scaled about the centre. */
    private void drawShaded(Canvas canvas, Shader shader, float cx, float cy, float r, float alpha, float scale) {
        if (alpha <= 0) return;
        fill.setShader(shader);
        fill.setAlpha(Math.round(255 * clamp01(alpha)));
        canvas.save();
        canvas.scale(scale, scale, cx, cy);
        canvas.drawCircle(cx, cy, r, fill);
        canvas.restore();
        fill.setShader(null);
        fill.setAlpha(255);
    }

    private void drawTopHighlight(Canvas canvas, float cx, float cy, int color, float scale) {
        float r = dp(CORE_DP) / 2f * scale;
        stroke.setShader(null);
        stroke.setStrokeWidth(dp(1));
        stroke.setColor(color);
        // A short top arc suggesting a 1px inset highlight.
        canvas.drawArc(cx - r + dp(2), cy - r + dp(2), cx + r - dp(2), cy + r - dp(2),
                200f, 140f, false, stroke);
    }

    private void drawGlyph(Canvas canvas, float cx, float cy, int color, boolean filled, float alpha) {
        float r = dp(GLYPH_DP) / 2f * 0.62f; // arc radius
        glyph.reset();
        // Power ring: 300° arc with a gap centered at the top (12 o'clock).
        glyph.addArc(cx - r, cy - r, cx + r, cy + r, -60f, 300f);
        glyphPaint.setColor(withAlpha(color, alpha));
        glyphPaint.setStyle(Paint.Style.STROKE);
        glyphPaint.setStrokeCap(Paint.Cap.ROUND);
        glyphPaint.setStrokeWidth(dp(filled ? 5f : 3.5f));
        canvas.drawPath(glyph, glyphPaint);
        // Vertical bar into the top gap.
        canvas.drawLine(cx, cy - r * 1.35f, cx, cy - r * 0.15f, glyphPaint);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private int withAlpha(int color, float a) {
        int base = color;
        int alpha = Math.round(Color.alpha(base) * clamp01(a));
        return Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base));
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }
}
