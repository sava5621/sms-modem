package local.modem.sms;

public final class LedPolicyTest {
    private static int checks;
    private static void check(boolean value,String description) {
        if(!value)throw new AssertionError(description);checks++;
    }
    private static void healthy(LedPolicy policy) { policy.network(true,true,true,"READY"); }
    public static void main(String[] args) {
        LedPolicy p=new LedPolicy();
        check(p.color(10000)==LedPolicy.Color.BLUE,"Unknown initial radio warns");
        healthy(p);
        check(p.color(10000)==LedPolicy.Color.OFF,"Healthy idle is dark");
        p.pageOpened(10000);
        check(p.color(10000)==LedPolicy.Color.GREEN,"Visit starts green");
        check(p.color(14999)==LedPolicy.Color.GREEN,"Green lasts through 4999 ms");
        check(p.color(15000)==LedPolicy.Color.OFF,"Off at 5000 ms boundary");
        check(p.remaining(15001)==0,"Remaining time never negative");
        p.pageOpened(20000);p.pageOpened(24000);
        check(p.color(25000)==LedPolicy.Color.GREEN,"New visit extends lease");
        check(p.color(28999)==LedPolicy.Color.GREEN,"Extended deadline holds");
        check(p.color(29000)==LedPolicy.Color.OFF,"Extended deadline expires");
        p.pageOpened(30000);
        p.network(true,false,false,"ABSENT");
        check(p.color(30001)==LedPolicy.Color.BLUE&&p.fault().equals("NO_SIM"),"Missing SIM overrides visit");
        healthy(p);
        check(p.color(32000)==LedPolicy.Color.GREEN,"Recovery resumes remaining lease");
        p.network(true,true,false,"READY");
        check(p.color(33000)==LedPolicy.Color.BLUE&&p.fault().equals("NO_NETWORK"),"No registration overrides visit");
        check(p.color(40000)==LedPolicy.Color.BLUE,"Fault persists after visit expiry");
        healthy(p);
        check(p.color(40001)==LedPolicy.Color.OFF,"Recovery after expiry returns to dark");
        p.network(true,false,false,"PIN_REQUIRED");
        check(p.fault().equals("SIM_NOT_READY")&&p.color(50000)==LedPolicy.Color.BLUE,"Locked SIM warns");
        p.network(false,false,false,"UNKNOWN");
        check(p.fault().equals("RADIO_UNKNOWN"),"Unavailable monitor warns");
        healthy(p);
        // Registration remains sufficient when dBm is weak or unavailable.
        check(p.color(50000)==LedPolicy.Color.OFF,"No data/signal measurement needed when registered");
        p.pageOpened(100000000000L);
        check(p.color(100000004999L)==LedPolicy.Color.GREEN,"Long uptime supported");
        check(p.color(100000005000L)==LedPolicy.Color.OFF,"Long uptime deadline expires");
        System.out.println("PASS LED policy: "+checks+" checks");
    }
}
