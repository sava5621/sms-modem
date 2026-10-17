package local.modem.sms;

/** Hardware-independent policy. Times are monotonic milliseconds, never wall clock. */
public final class LedPolicy {
    public static final long VISIT_MILLIS=5000;
    public enum Color { OFF, GREEN, BLUE }
    private long visitUntil;
    private String fault="RADIO_UNKNOWN";
    public synchronized void network(boolean available,boolean simReady,boolean registered,String simState) {
        fault="ABSENT".equals(simState)?"NO_SIM":!available?"RADIO_UNKNOWN":!simReady?"SIM_NOT_READY":!registered?"NO_NETWORK":"";
    }
    public synchronized void pageOpened(long now) { visitUntil=now+VISIT_MILLIS; }
    public synchronized Color color(long now) { return !fault.isEmpty()?Color.BLUE:now<visitUntil?Color.GREEN:Color.OFF; }
    public synchronized long remaining(long now) { return Math.max(0,visitUntil-now); }
    public synchronized String fault() { return fault; }
}
