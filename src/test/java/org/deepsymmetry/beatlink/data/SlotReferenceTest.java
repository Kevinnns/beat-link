package org.deepsymmetry.beatlink.data;

import org.deepsymmetry.beatlink.CdjStatus;
import org.junit.Test;

public class SlotReferenceTest {
    @Test(expected = NullPointerException.class)
    public void getSlotReferenceRejectsNullSlot() {
        SlotReference.getSlotReference(1, null);
    }
}
