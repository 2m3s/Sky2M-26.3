// Ported from Skysoft (https://github.com/Akinsoft/Skysoft), features/helditem/HeldItemEditorHistory.kt and
// utils/SnapshotHistory.kt.
// SPDX-License-Identifier: LGPL-3.0-only
package com.epic60869.sky2m.features.helditem;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Undo and redo in the editor, kept separately for the global settings and each item, for 15 minutes after the last
 * change. A drag counts as one step, and scrolling one slider counts as one step until it pauses.
 */
final class HeldItemEditorHistory {
    private static final int MAX_STEPS = 16;
    private static final long EXPIRATION_NANOS = 15L * 60L * 1_000_000_000L;
    private static final long SCROLL_COALESCE_NANOS = 350_000_000L;

    /** Whose settings an edit changed: the global ones (null) or one item's. */
    record Key(String itemId) {
        static final Key GLOBAL = new Key(null);

        static Key item(String itemId) {
            return new Key(HeldItemConfig.normalized(itemId));
        }
    }

    record Edit(Key key, HeldItemConfig.CustomizationSnapshot before) {}

    private record Step(HeldItemConfig.CustomizationSnapshot before, HeldItemConfig.CustomizationSnapshot after) {}

    private static final class ContextHistory {
        final ArrayDeque<Step> undo = new ArrayDeque<>();
        final ArrayDeque<Step> redo = new ArrayDeque<>();
        long lastTouchedAtNanos;
    }

    /** Histories live as long as the game, so reopening the editor can still undo. */
    static final class Store {
        private final Map<Key, ContextHistory> histories = new HashMap<>();

        Edit begin(HeldItemConfig config, Key key) {
            expire();
            return new Edit(key, config.snapshotCustomization(key.itemId()));
        }

        boolean commit(HeldItemConfig config, Edit edit) {
            expire();
            HeldItemConfig.CustomizationSnapshot after = config.snapshotCustomization(edit.key().itemId());
            if (after.equals(edit.before())) return false;
            ContextHistory history = histories.computeIfAbsent(edit.key(), k -> new ContextHistory());
            history.undo.addLast(new Step(edit.before(), after));
            while (history.undo.size() > MAX_STEPS) history.undo.removeFirst();
            history.redo.clear();
            history.lastTouchedAtNanos = System.nanoTime();
            return true;
        }

        boolean canUndo(Key key) {
            expire();
            ContextHistory h = histories.get(key);
            return h != null && !h.undo.isEmpty();
        }

        boolean canRedo(Key key) {
            expire();
            ContextHistory h = histories.get(key);
            return h != null && !h.redo.isEmpty();
        }

        boolean undo(HeldItemConfig config, Key key) {
            expire();
            ContextHistory h = histories.get(key);
            if (h == null || h.undo.isEmpty()) return false;
            Step step = h.undo.removeLast();
            config.restoreCustomization(step.before());
            h.redo.addLast(step);
            while (h.redo.size() > MAX_STEPS) h.redo.removeFirst();
            h.lastTouchedAtNanos = System.nanoTime();
            return true;
        }

        boolean redo(HeldItemConfig config, Key key) {
            expire();
            ContextHistory h = histories.get(key);
            if (h == null || h.redo.isEmpty()) return false;
            Step step = h.redo.removeLast();
            config.restoreCustomization(step.after());
            h.undo.addLast(step);
            while (h.undo.size() > MAX_STEPS) h.undo.removeFirst();
            h.lastTouchedAtNanos = System.nanoTime();
            return true;
        }

        private void expire() {
            long now = System.nanoTime();
            histories.values().removeIf(h -> now - h.lastTouchedAtNanos >= EXPIRATION_NANOS);
        }
    }

    /** One editor's view of the history: groups drags and scrolling into single steps. */
    static final class Controller {
        private final HeldItemConfig config;
        private final Store store;
        private final Supplier<Key> keyProvider;
        private Edit gestureEdit;
        private Edit scrollEdit;
        private TransformField scrollField;
        private long scrollChangedAtNanos;

        Controller(HeldItemConfig config, Store store, Supplier<Key> keyProvider) {
            this.config = config;
            this.store = store;
            this.keyProvider = keyProvider;
        }

        void beginGesture() {
            commitScroll();
            Key key = keyProvider.get();
            gestureEdit = key == null ? null : store.begin(config, key);
        }

        boolean commitGesture() {
            Edit edit = gestureEdit;
            if (edit == null) return false;
            gestureEdit = null;
            return store.commit(config, edit);
        }

        boolean mutate(Supplier<Boolean> action) {
            flushPending();
            Key key = keyProvider.get();
            if (key == null) return false;
            Edit edit = store.begin(config, key);
            action.get();
            return store.commit(config, edit);
        }

        boolean mutateScroll(TransformField field, Runnable action) {
            Key key = keyProvider.get();
            if (key == null) return false;
            long now = System.nanoTime();
            if (scrollEdit == null || !scrollEdit.key().equals(key) || scrollField != field
                || now - scrollChangedAtNanos >= SCROLL_COALESCE_NANOS) {
                commitScroll();
                scrollEdit = store.begin(config, key);
                scrollField = field;
            }
            HeldItemConfig.CustomizationSnapshot before = config.snapshotCustomization(key.itemId());
            action.run();
            scrollChangedAtNanos = now;
            return !before.equals(config.snapshotCustomization(key.itemId()));
        }

        void commitIdleScroll() {
            if (scrollEdit != null && System.nanoTime() - scrollChangedAtNanos >= SCROLL_COALESCE_NANOS) commitScroll();
        }

        void flushPending() {
            commitGesture();
            commitScroll();
        }

        boolean canUndo() {
            Key key = keyProvider.get();
            return key != null && (hasPendingChange(key) || store.canUndo(key));
        }

        boolean canRedo() {
            Key key = keyProvider.get();
            return key != null && !hasPendingChange(key) && store.canRedo(key);
        }

        boolean undo() {
            flushPending();
            Key key = keyProvider.get();
            return key != null && store.undo(config, key);
        }

        boolean redo() {
            flushPending();
            Key key = keyProvider.get();
            return key != null && store.redo(config, key);
        }

        private boolean commitScroll() {
            Edit edit = scrollEdit;
            if (edit == null) return false;
            scrollEdit = null;
            scrollField = null;
            return store.commit(config, edit);
        }

        private boolean hasPendingChange(Key key) {
            Edit edit = gestureEdit != null && gestureEdit.key().equals(key) ? gestureEdit
                : scrollEdit != null && scrollEdit.key().equals(key) ? scrollEdit : null;
            return edit != null && !Objects.equals(edit.before(), config.snapshotCustomization(key.itemId()));
        }
    }
}
