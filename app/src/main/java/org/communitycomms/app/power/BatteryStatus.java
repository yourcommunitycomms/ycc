package org.communitycomms.app.power;

/** One reading from any battery. Watts; -1 / NONE where the unit does not say. */
public final class BatteryStatus {
    public static final int NONE = Integer.MIN_VALUE;
    public long time;
    /** Battery % (0-100), -1 not known. */
    public int soc = -1;
    /** Power into the battery side (charging sources) and out to loads. */
    public int acIn, dcIn, acOut, dcOut;
    /** 1 on, 0 off, -1 not known (shown only; this app never switches outputs). */
    public int acOn = -1, dcOn = -1;
    /** Mains / grid: 1 up, 0 down, -1 work it out from acIn. */
    public int mains = -1;
    /** Home batteries (Powerwall): grid (+ import), solar, home load, battery (+ discharging). */
    public int gridW = NONE, solarW = NONE, loadW = NONE, batteryW = NONE;
    /** Battery size the unit reported (Wh), 0 if not. */
    public int capWh;
    /** Minutes left the unit reported itself, -1 if not. */
    public int minutesLeft = -1;
    public String model = "";
    /** Extra lines for the screen ("Grid: connected", "Temperature 24 C"). */
    public String detail = "";

    public int in() {
        return acIn + dcIn;
    }

    public int out() {
        return acOut + dcOut;
    }

    public boolean mainsUp() {
        return mains >= 0 ? mains == 1 : acIn > 5;
    }
}
