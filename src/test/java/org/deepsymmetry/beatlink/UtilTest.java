package org.deepsymmetry.beatlink;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Tests for {@link Util#translateOpusPlayerNumbers(int)}.
 */
public class UtilTest {

    @Test
    public void translateReturnsOneThroughFour() {
        for (int i = 1; i <= 4; i++) {
            assertEquals(i, Util.translateOpusPlayerNumbers(i));
        }
        for (int i = 9; i <= 12; i++) {
            assertEquals(i - 8, Util.translateOpusPlayerNumbers(i));
        }
    }

    @Test
    public void otherValuesRemainUnchanged() {
        int[] others = {0, 5, 6, 7, 8, 13, 14, 15};
        for (int v : others) {
            assertEquals(v, Util.translateOpusPlayerNumbers(v));
        }
    }
}
