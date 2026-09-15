# ThreadCity Collector

Build and capture a short JVM incident window:

```bash
mvn clean package
java -jar target/threadcity-collector-0.1.0-SNAPSHOT.jar \
  --pid 12345 --duration 20 --snapshots 3 --output incident.threadcity
```

The collector uses the current JDK 25 `jcmd` binary. It creates a ZIP-compatible `.threadcity` bundle containing:

- 2–5 chronological `Thread.print -l` snapshots for lock and synchronizer analysis;
- one structured `Thread.dump_to_file -format=json` snapshot that includes platform and virtual threads;
- a profile JFR recording; and
- an exact manifest that orders every snapshot and records its capture time.

It refuses to overwrite an existing output file and removes its temporary capture workspace after the bundle is closed.
