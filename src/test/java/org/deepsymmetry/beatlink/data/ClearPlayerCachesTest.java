package org.deepsymmetry.beatlink.data;

import org.deepsymmetry.beatlink.CdjStatus;
import org.deepsymmetry.beatlink.VirtualRekordbox;
import org.deepsymmetry.beatlink.data.SlotReference;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.Assert.*;

public class ClearPlayerCachesTest {

    @Test
    public void detachClearsCachedPlayerMappings() throws Exception {
        OpusProvider provider = OpusProvider.getInstance();
        VirtualRekordbox vrb = VirtualRekordbox.getInstance();

        Field trackSourceField = VirtualRekordbox.class.getDeclaredField("playerTrackSourceSlots");
        trackSourceField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, SlotReference> trackSourceMap = (Map<Integer, SlotReference>) trackSourceField.get(vrb);

        Field deviceSqlField = VirtualRekordbox.class.getDeclaredField("playerToDeviceSqlRekordboxId");
        deviceSqlField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, Integer> deviceSqlMap = (Map<Integer, Integer>) deviceSqlField.get(vrb);

        // Clear any existing state
        trackSourceMap.clear();
        deviceSqlMap.clear();

        // Player 2 mapping for slot 1
        trackSourceMap.put(2, SlotReference.getSlotReference(1, CdjStatus.TrackSourceSlot.USB_SLOT));
        deviceSqlMap.put(2, 1234);
        // Player 3 mapping for slot 2
        trackSourceMap.put(3, SlotReference.getSlotReference(2, CdjStatus.TrackSourceSlot.USB_SLOT));
        deviceSqlMap.put(3, 5678);

        // Detach archive from slot 1
        provider.attachMetadataArchive(null, 1);

        assertFalse(trackSourceMap.containsKey(2));
        assertFalse(deviceSqlMap.containsKey(2));
        assertTrue(trackSourceMap.containsKey(3));
        assertTrue(deviceSqlMap.containsKey(3));
    }
}
