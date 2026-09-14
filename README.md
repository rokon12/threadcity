# ThreadCity

**Turn unreadable Java thread dumps into an interactive map of exactly who is blocking whom.**

ThreadCity is a Vaadin Flow incident tool that parses HotSpot thread dumps, reconstructs lock ownership and wait relationships, detects circular lock dependencies, groups repeated stack traces, and explains the result in a visual operations dashboard.

## Try the 30-second demo

1. Open the application—there is no account or sign-in.
2. Click **Replay a production deadlock**.
3. Follow the animated red lanes between `checkout-37` and `inventory-sync-12`.
4. Open **Time machine** and scrub from healthy traffic through contention and into the circular wait.
5. Open **Threads & evidence** and select either thread to reveal the responsive Master-Detail inspector.
6. Drag the **Compare fix** divider between the broken and corrected snapshots.
7. Open **AI copilot** to see the safe public-demo state, or enable it locally with your own key to investigate using Vaadin Messages.

You can also upload real evidence or use the included demo files:

- [`jstack.txt`](src/main/resources/static/examples/jstack.txt) contains a concise two-thread deadlock.
- [`threadcity-demo.jfr`](src/main/resources/static/examples/threadcity-demo.jfr) is a real 10-second JDK Flight Recorder capture from the included failure lab, with CPU pressure and custom incident signals.

Both are downloadable from the application UI.

## Privacy and safety

- Uploads are limited to one 5 MiB UTF-8 text file and processed in memory.
- Raw upload bytes are not persisted or logged.
- The immutable analysis remains only in the user's Vaadin session until **Clear analysis** is selected or the session ends.
- The hosted demo makes no external AI calls and has no shared provider key. AI is opt-in for local/self-hosted copies and requires explicit confirmation before the first request for each analyzed snapshot. ThreadCity sends a bounded summary—not the raw dump—containing thread names, states, lock relationships, findings, representative top frames, the user's question, and up to five prior conversation turns to the configured provider through LangChain4j.
- Every provider call passes through hard per-session, per-network, concurrent, and global daily ceilings. Production persists the global counter across restarts and supports a fail-closed filesystem kill switch.
- AI output is advisory. The parser, wait graph, and deadlock detection remain deterministic and work without an API key.
- Empty, malformed, binary-looking, excessive-line, and unrecognized files are rejected with a concise message.
- Demo replays use recorded thread dumps and short-lived virtual threads; the application never creates an actual deadlock.

## Capabilities

- HotSpot/JDK 17–25-style thread headers and states.
- Monitor-entry, object-wait, parking-wait, owned-monitor, and ownable-synchronizer parsing.
- Conservative waiter-to-owner graph construction: ambiguous ownership and notification waits do not become confirmed deadlocks.
- Deterministic cycle detection and repeated-stack clustering.
- Interactive lock map, searchable/filterable Grid, finding navigation, stack inspector, and copyable incident summary.
- Four-snapshot Incident Time Machine with Vaadin Slider, Progress Bar, Push-powered playback, and a real intermediate contention fixture.
- Responsive thread investigation using Grid, Grid Context Menu, and Master-Detail Layout.
- Draggable broken-vs-fixed comparison using Split Layout and Details.
- Optional LangChain4j copilot using OpenAI, Vaadin Message List and Message Input, cancellable token streaming over Vaadin Push, snapshot-scoped conversation memory, clickable deterministic evidence, contextual Ask AI actions, prompt-injection boundaries, explicit data disclosure, hard usage ceilings, and graceful failure isolation.
- Built-in healthy, contention, deadlocked, and corrected checkout snapshots.
- Responsive layout and reduced-motion support.

## Requirements

- JDK 25
- Maven 3.9+
- Node.js 22+ for development mode

## Run locally

```bash
mvn test
VAADIN_USAGE_STATS_ENABLED=false mvn spring-boot:run
```

Open <http://localhost:8080>.

## Production build

```bash
mvn -Pproduction clean verify
java -jar target/threadcity-0.1.0-SNAPSHOT.jar
```

The server respects the `PORT` environment variable.

## Try the AI copilot with your own key

AI is disabled by default, including on the public demo. To try the LangChain4j copilot, clone the project and opt in to OpenAI with a key from your own account. Keep the key in your local environment—never in source control or browser storage.

```bash
git clone https://github.com/rokon12/threadcity.git
cd threadcity
export THREADCITY_AI_PROVIDER="openai"
export OPENAI_API_KEY="your-api-key"
export THREADCITY_AI_MODEL="gpt-4.1-mini" # optional
VAADIN_USAGE_STATS_ENABLED=false mvn spring-boot:run
```

Then open <http://localhost:8080>, replay or upload an incident, and select **AI copilot**. The key remains in the local server process; it is never sent to or stored by the hosted ThreadCity demo.

Provider calls are guarded by defaults of 6 calls per browser session per UTC day, 12 calls per network per hour, 100 calls globally per UTC day, and 2 concurrent calls. Override them with `THREADCITY_AI_SESSION_DAILY_LIMIT`, `THREADCITY_AI_NETWORK_HOURLY_LIMIT`, `THREADCITY_AI_GLOBAL_DAILY_LIMIT`, and `THREADCITY_AI_CONCURRENT_LIMIT`. A self-hosted deployment can persist its counter and configure a kill switch with `THREADCITY_AI_USAGE_FILE` and `THREADCITY_AI_KILL_SWITCH_FILE`.

Creating the configured kill-switch file disables AI immediately and safely:

```bash
touch /var/lib/threadcity/AI_DISABLED
```

Remove it only when AI should accept requests again. When AI is absent, disabled, rate-limited, or unavailable, every deterministic feature remains available and the analysis result is unchanged.

## Container

```bash
docker build -t threadcity .
docker run --rm -p 8080:8080 -e PORT=8080 threadcity
```

The final container runs as a non-root user.

## Architecture

```text
ca.bazlur.threadcity
├── ai             optional bounded prompt and LangChain4j explanation service
├── analysis       deterministic wait graph, cycles, clusters, findings
├── application    parse/analyze use cases and built-in sample access
├── domain         immutable analysis model
├── parser         HotSpot parser and bounded upload validation
└── ui
    ├── component  focused Vaadin workspaces: map, timeline, evidence, compare, AI
    ├── support    presentation-only formatting helpers
    └── MainView   route-level workflow coordinator
```

The route coordinates user intent while each workspace owns its own controls and transient UI state. The core parser and analyzer have no Vaadin or Spring dependency. Thread names are display data—not identity—and unknown or incomplete information degrades to an unresolved wait instead of a confident diagnosis.

## Vaadin showcase

The competition workflow deliberately uses Vaadin components as product behavior rather than decoration:

- `AppLayout`, `DrawerToggle`, and `SideNav` provide the application shell.
- `Tabs` organize the investigation into map, time-machine, evidence, comparison, and AI workspaces.
- `Grid`, `GridContextMenu`, and `MasterDetailLayout` power thread exploration.
- `IntegerSlider`, `ProgressBar`, and server Push animate the incident replay.
- `SplitLayout` and `Details` explain the fix visually.
- `MessageList`, `MessageInput`, `ConfirmDialog`, Push, and contextual Buttons provide the streaming opt-in AI investigation flow and navigate AI references back to deterministic evidence.
- `Upload` and `Notification` handle bounded in-memory analysis and feedback.

## Technology

- Java 25
- Spring Boot 4
- Vaadin 25
- LangChain4j 1.20 (optional, user-configured OpenAI integration)
- JUnit 5 and AssertJ

No database, authentication, commercial Vaadin component, or external graph library is required. The LLM integration is optional.
