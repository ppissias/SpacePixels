package eu.startales.spacepixels.skycatalog;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The field outline, including a field that contains the celestial pole. */
public class SkyFieldTest {

    /** The centre is inside, and a point well outside the corners is not. */
    @Test
    public void theCentreIsInsideAndADistantPointIsNot() {
        SkyField field = square(10, 20, 1);
        assertTrue(field.containsSky(10, 20));
        assertFalse(field.containsSky(10, 40));
    }

    /**
     * Corners around a centre close to the pole enclose the pole and a star on the far side of it. A flat RA/Dec
     * polygon of those corners does not.
     */
    @Test
    public void aFieldAroundThePoleContainsThePole() {
        SkyField field = new SkyField();
        field.centerRa = 0;
        field.centerDec = 89.8;
        field.cornerRa = new double[] {0, 90, 180, 270};
        field.cornerDec = new double[] {89.5, 89.5, 89.5, 89.5};
        assertTrue(field.containsSky(0, 90));
        assertTrue(field.containsSky(350, 89.7));
        assertFalse(field.containsSky(0, 80));
    }

    /** A small field at high declination still rejects a point outside its corners. */
    @Test
    public void aHighDeclinationFieldKeepsItsCorners() {
        SkyField field = square(30, 85, 0.4);
        assertTrue(field.containsSky(30.1, 85.1));
        assertFalse(field.containsSky(30, 87));
    }

    /** A square of the given side, in degrees, centred on the given sky position. */
    private static SkyField square(double ra, double dec, double side) {
        SkyField field = new SkyField();
        field.centerRa = ra;
        field.centerDec = dec;
        double half = side / 2;
        field.cornerRa = new double[] {ra - half, ra + half, ra + half, ra - half};
        field.cornerDec = new double[] {dec - half, dec - half, dec + half, dec + half};
        return field;
    }
}
