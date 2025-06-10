package org.deepsymmetry.beatlink.data;

import org.deepsymmetry.beatlink.CdjStatus;
import org.deepsymmetry.beatlink.MediaDetails;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.Assert.*;

public class MetadataFinderTest {

    @Test
    public void recordMountUsesSlotForOpusProvider() throws Exception {
        OpusProvider provider = OpusProvider.getInstance();
        provider.start();

        Field queueField = OpusProvider.class.getDeclaredField("archiveAttachQueueMap");
        queueField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, LinkedBlockingQueue<MediaDetails>> queueMap =
                (Map<Integer, LinkedBlockingQueue<MediaDetails>>) queueField.get(provider);

        LinkedBlockingQueue<MediaDetails> q1 = queueMap.get(1);
        LinkedBlockingQueue<MediaDetails> q2 = queueMap.get(2);
        q1.clear();
        q2.clear();

        q1.add(new MediaDetails(SlotReference.getSlotReference(1, CdjStatus.TrackSourceSlot.SD_SLOT),
                CdjStatus.TrackType.REKORDBOX, "", 0, 0, 0));
        q2.add(new MediaDetails(SlotReference.getSlotReference(1, CdjStatus.TrackSourceSlot.USB_SLOT),
                CdjStatus.TrackType.REKORDBOX, "", 0, 0, 0));

        Method recordMount = MetadataFinder.class.getDeclaredMethod("recordMount", SlotReference.class);
        recordMount.setAccessible(true);

        recordMount.invoke(MetadataFinder.getInstance(),
                SlotReference.getSlotReference(99, CdjStatus.TrackSourceSlot.SD_SLOT));
        assertTrue(q1.isEmpty());
        assertEquals(1, q2.size());

        recordMount.invoke(MetadataFinder.getInstance(),
                SlotReference.getSlotReference(100, CdjStatus.TrackSourceSlot.USB_SLOT));
        assertTrue(q2.isEmpty());

        provider.stop();
    }
}
