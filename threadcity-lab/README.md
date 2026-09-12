# ThreadCity Failure Lab

This separate Java 25 project deliberately creates several JVM thread pathologies. Run it only on a development machine.

```bash
mvn clean package
java -jar target/threadcity-failure-lab-0.1.0-SNAPSHOT.jar --duration 120
```

The process prints its PID and the matching collector command. In another terminal, build `../threadcity-collector`, run that command, and upload the resulting `.threadcity` file into ThreadCity.

The lab creates:

- a two-thread intrinsic-monitor deadlock;
- eight threads convoying behind one monitor owner;
- a fixed executor whose workers wait for tasks queued to the same executor;
- six callers waiting on an empty synthetic connection pool;
- four socket readers whose local server never responds;
- two CPU-spinning platform threads;
- 80 gated virtual threads and custom `threadcity.lab.PressurePulse` JFR events.

All non-deadlocked resources are released when the duration ends. The deadlocked threads are daemon threads, so they cannot keep the process alive.
