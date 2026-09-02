package com.cabletv.player.config;

import static org.junit.Assert.assertEquals;

import android.view.KeyEvent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.orhanobut.hawk.Hawk;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * Key-mapping tests for the source-switch action added for issue #5 (manual 源1/2 switching):
 * the default resolves to a keycode, and upgrading an older stored mapping fills in the new
 * action with its default without overwriting user rebinds.
 */
@RunWith(RobolectricTestRunner.class)
public class KeyMappingSourceSwitchTest {

    @Before
    public void setUp() {
        Hawk.init(RuntimeEnvironment.getApplication()).build();
    }

    @Test
    public void sourceSwitch_defaultAction_resolvesToR() {
        KeyMapping.resetToDefaults();
        KeyMapping.migrateIfNeeded();
        assertEquals(KeyMapping.Action.SOURCE_SWITCH, KeyMapping.resolve(KeyEvent.KEYCODE_R));
        assertEquals(KeyEvent.KEYCODE_R, KeyMapping.getKeyCode(KeyMapping.Action.SOURCE_SWITCH));
    }

    @Test
    public void numericActions_remainUntouched() {
        KeyMapping.resetToDefaults();
        KeyMapping.migrateIfNeeded();
        // NUM_0..NUM_9 stay contiguous, so typing a number still works after the new action.
        for (int i = 0; i <= 9; i++) {
            int keyCode = KeyEvent.KEYCODE_0 + i;
            assertEquals(KeyMapping.Action.valueOf("NUM_" + i), KeyMapping.resolve(keyCode));
        }
        // The source-switch key does not collide with the numeric block.
        assertEquals(KeyMapping.Action.SOURCE_SWITCH, KeyMapping.resolve(KeyEvent.KEYCODE_R));
    }

    @Test
    public void migration_fillsSourceSwitchWithoutClobberingRebinds() {
        // A v1 mapping with a user rebind (CHANNEL_UP = KEYCODE_A) and no SOURCE_SWITCH key.
        JsonObject old = new JsonObject();
        old.addProperty(KeyMapping.Action.CHANNEL_UP.name(), KeyEvent.KEYCODE_A);
        old.addProperty(KeyMapping.Action.CHANNEL_DOWN.name(), KeyEvent.KEYCODE_DPAD_DOWN);
        old.addProperty(KeyMapping.Action.VOLUME_UP.name(), KeyEvent.KEYCODE_VOLUME_UP);
        old.addProperty(KeyMapping.Action.VOLUME_DOWN.name(), KeyEvent.KEYCODE_VOLUME_DOWN);
        old.addProperty(KeyMapping.Action.OK.name(), KeyEvent.KEYCODE_DPAD_CENTER);
        old.addProperty(KeyMapping.Action.MENU.name(), KeyEvent.KEYCODE_MENU);
        old.addProperty(KeyMapping.Action.BACK.name(), KeyEvent.KEYCODE_BACK);
        old.addProperty(KeyMapping.Action.LEFT.name(), KeyEvent.KEYCODE_DPAD_LEFT);
        old.addProperty(KeyMapping.Action.RIGHT.name(), KeyEvent.KEYCODE_DPAD_RIGHT);
        old.addProperty(KeyMapping.Action.NUM_0.name(), KeyEvent.KEYCODE_0);
        old.addProperty(KeyMapping.Action.NUM_1.name(), KeyEvent.KEYCODE_1);
        old.addProperty(KeyMapping.Action.NUM_2.name(), KeyEvent.KEYCODE_2);
        old.addProperty(KeyMapping.Action.NUM_3.name(), KeyEvent.KEYCODE_3);
        old.addProperty(KeyMapping.Action.NUM_4.name(), KeyEvent.KEYCODE_4);
        old.addProperty(KeyMapping.Action.NUM_5.name(), KeyEvent.KEYCODE_5);
        old.addProperty(KeyMapping.Action.NUM_6.name(), KeyEvent.KEYCODE_6);
        old.addProperty(KeyMapping.Action.NUM_7.name(), KeyEvent.KEYCODE_7);
        old.addProperty(KeyMapping.Action.NUM_8.name(), KeyEvent.KEYCODE_8);
        old.addProperty(KeyMapping.Action.NUM_9.name(), KeyEvent.KEYCODE_9);
        Hawk.put("app_key_mapping", old.toString());
        // Simulate a pre-source-switch install: version older than the current schema.
        Hawk.put("app_key_mapping_version", 1);

        KeyMapping.migrateIfNeeded();

        // The user rebind survives...
        assertEquals(KeyEvent.KEYCODE_A, KeyMapping.getKeyCode(KeyMapping.Action.CHANNEL_UP));
        // ...while the new action gets its default.
        assertEquals(KeyEvent.KEYCODE_R, KeyMapping.getKeyCode(KeyMapping.Action.SOURCE_SWITCH));
        // Old keys keep resolving.
        assertEquals(KeyMapping.Action.CHANNEL_UP, KeyMapping.resolve(KeyEvent.KEYCODE_A));
        // And the version is stamped so the migration never runs again.
        JsonObject after = JsonParser.parseString(Hawk.get("app_key_mapping", "{}"))
                .getAsJsonObject();
        assertEquals(KeyEvent.KEYCODE_R,
                after.get(KeyMapping.Action.SOURCE_SWITCH.name()).getAsInt());
    }
}