// Ported from Skysoft (https://github.com/Akinsoft/Skysoft), src/main/kotlin/com/skysoft/config/HeldItemConfig.kt
// (HeldItemTransformConfig, HeldItemTransformLimits, HeldItemSwingStyle, HeldItemTextureMode and the snapshots).
// SPDX-License-Identifier: LGPL-3.0-only
package com.epic60869.sky2m.features.helditem;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

/** Where the held item sits, how big it is, how it's turned and how it swings. */
public final class HeldItemTransform {
    public static final float MIN_X = -2f, MAX_X = 2f;
    public static final float MIN_Y = -2f, MAX_Y = 2f;
    public static final float MIN_Z = -1.5f, MAX_Z = 0.6f;
    public static final float MIN_SCALE = 0.25f, MAX_SCALE = 3f;
    public static final float MIN_SWING_SPEED = 0.25f, MAX_SWING_SPEED = 3f;
    public static final float MIN_ROTATION = -180f, MAX_ROTATION = 180f;

    public enum SwingStyle { VANILLA, ITEM_ONLY }

    public enum TextureMode {
        PACK, VANILLA;

        TextureMode toggled() {
            return this == PACK ? VANILLA : PACK;
        }
    }

    @Expose public float x;
    @Expose public float y;
    @Expose public float z;
    @Expose public float scale = 1f;
    @Expose @SerializedName(value = "swingSpeed", alternate = {"swingDuration"}) public float swingSpeed = 1f;
    @Expose public SwingStyle swingStyle = SwingStyle.VANILLA;
    @Expose public float rotationX;
    @Expose public float rotationY;
    @Expose public float rotationZ;

    public HeldItemTransform copyValues() {
        HeldItemTransform copy = new HeldItemTransform();
        copy.restore(snapshot());
        return copy;
    }

    public void reset() {
        restore(new HeldItemTransform().snapshot());
    }

    public void repairLoadedValues() {
        x = clamp(x, MIN_X, MAX_X);
        y = clamp(y, MIN_Y, MAX_Y);
        z = clamp(z, MIN_Z, MAX_Z);
        scale = clamp(scale, MIN_SCALE, MAX_SCALE);
        swingSpeed = clamp(swingSpeed, MIN_SWING_SPEED, MAX_SWING_SPEED);
        if (swingStyle == null) swingStyle = SwingStyle.VANILLA;
        rotationX = clamp(rotationX, MIN_ROTATION, MAX_ROTATION);
        rotationY = clamp(rotationY, MIN_ROTATION, MAX_ROTATION);
        rotationZ = clamp(rotationZ, MIN_ROTATION, MAX_ROTATION);
    }

    public boolean hasRenderChanges() {
        return x != 0f || y != 0f || z != 0f || scale != 1f || rotationX != 0f || rotationY != 0f || rotationZ != 0f;
    }

    public boolean isDefault() {
        return !hasRenderChanges() && swingSpeed == 1f && swingStyle == SwingStyle.VANILLA;
    }

    Snapshot snapshot() {
        return new Snapshot(x, y, z, scale, swingSpeed, swingStyle, rotationX, rotationY, rotationZ);
    }

    void restore(Snapshot s) {
        x = s.x;
        y = s.y;
        z = s.z;
        scale = s.scale;
        swingSpeed = s.swingSpeed;
        swingStyle = s.swingStyle;
        rotationX = s.rotationX;
        rotationY = s.rotationY;
        rotationZ = s.rotationZ;
    }

    static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    record Snapshot(float x, float y, float z, float scale, float swingSpeed, SwingStyle swingStyle,
                    float rotationX, float rotationY, float rotationZ) {
        HeldItemTransform toTransform() {
            HeldItemTransform t = new HeldItemTransform();
            t.restore(this);
            return t;
        }
    }
}
