package net.kdt.pojavlaunch.progresskeeper;

public class ProgressState {
    int progress;
    int resid;
    Object[] varArg;

    /** Last reported percentage (0..100). */
    public int getProgress() { return progress; }
    /** String resource of the current status line, or -1. */
    public int getResid() { return resid; }
    /** Formatting arguments for the status line (may include size/speed/ETA payloads). */
    public Object[] getVarArgs() { return varArg; }
}
