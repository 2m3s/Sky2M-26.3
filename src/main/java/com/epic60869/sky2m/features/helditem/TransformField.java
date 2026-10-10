// Ported from Skysoft (https://github.com/Akinsoft/Skysoft), features/helditem/TransformField.kt.
// SPDX-License-Identifier: LGPL-3.0-only
package com.epic60869.sky2m.features.helditem;

import java.util.List;
import java.util.Locale;

/** One slider in the editor. */
enum TransformField {
    X("X", HeldItemTransform.MIN_X, HeldItemTransform.MAX_X, 0.05f),
    Y("Y", HeldItemTransform.MIN_Y, HeldItemTransform.MAX_Y, 0.05f),
    Z("Z", HeldItemTransform.MIN_Z, HeldItemTransform.MAX_Z, 0.05f),
    SCALE("Scale", HeldItemTransform.MIN_SCALE, HeldItemTransform.MAX_SCALE, 0.05f),
    SWING("Swing", HeldItemTransform.MIN_SWING_SPEED, HeldItemTransform.MAX_SWING_SPEED, 0.05f),
    ROTATION_X("Rotate X", HeldItemTransform.MIN_ROTATION, HeldItemTransform.MAX_ROTATION, 5f),
    ROTATION_Y("Rotate Y", HeldItemTransform.MIN_ROTATION, HeldItemTransform.MAX_ROTATION, 5f),
    ROTATION_Z("Rotate Z", HeldItemTransform.MIN_ROTATION, HeldItemTransform.MAX_ROTATION, 5f);

    static final List<TransformField> BASIC = List.of(X, Y, Z, SCALE, SWING);
    static final List<TransformField> ROTATION = List.of(ROTATION_X, ROTATION_Y, ROTATION_Z);
    static final List<TransformField> ALL = List.of(values());

    final String label;
    final float min;
    final float max;
    final float step;

    TransformField(String label, float min, float max, float step) {
        this.label = label;
        this.min = min;
        this.max = max;
        this.step = step;
    }

    float value(HeldItemTransform t) {
        return switch (this) {
            case X -> t.x;
            case Y -> t.y;
            case Z -> t.z;
            case SCALE -> t.scale;
            case SWING -> t.swingSpeed;
            case ROTATION_X -> t.rotationX;
            case ROTATION_Y -> t.rotationY;
            case ROTATION_Z -> t.rotationZ;
        };
    }

    void setValue(HeldItemTransform t, float value) {
        switch (this) {
            case X -> t.x = value;
            case Y -> t.y = value;
            case Z -> t.z = value;
            case SCALE -> t.scale = value;
            case SWING -> t.swingSpeed = value;
            case ROTATION_X -> t.rotationX = value;
            case ROTATION_Y -> t.rotationY = value;
            case ROTATION_Z -> t.rotationZ = value;
        }
    }

    String formattedValue(float value) {
        if (ROTATION.contains(this)) return String.format(Locale.US, "%.0f°", value);
        String text = String.format(Locale.US, "%.2f", value);
        return this == SCALE || this == SWING ? text + "x" : text;
    }
}
