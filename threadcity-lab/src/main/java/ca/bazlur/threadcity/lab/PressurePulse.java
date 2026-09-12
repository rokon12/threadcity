package ca.bazlur.threadcity.lab;

import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

@Name("threadcity.lab.PressurePulse")
@Label("ThreadCity failure-lab pressure")
@StackTrace(true)
final class PressurePulse extends Event {

    @Label("Scenario")
    private String scenario;

    @Label("Pressure")
    private int pressure;

    @Label("Message")
    private String message;

    static void emit(String scenario, int pressure, String message) {
        PressurePulse event = new PressurePulse();
        event.scenario = scenario;
        event.pressure = pressure;
        event.message = message;
        event.commit();
    }
}
