# AI R&D Platform

A Java web application for collaborative tabular research: private CSV datasets, immutable versions, real CPU model training, comparable experiments, project discussions, and administrator controls.

Built in this folder from the supplied ZIP. The original planning documents are preserved under [docs/reference](docs/reference/README.md). Their proposed Python/React stack has been adapted to the requested Java application. The implemented architecture and its limits are documented below; the original plan is reference material, not a claim that every proposed deployment step has been completed.

## Start on Windows

Requires **JDK 21 or newer**. This project was compiled and exercised using the installed JDK 26. Maven 3.9.11 is bootstrapped inside the project; no system-wide Maven installation is needed.

From this folder:

~~~powershell
.\start.cmd
~~~

Open **http://127.0.0.1:8080**. The first build needs internet access to download Maven/dependencies. The verified JAR already exists in this workspace, so the current checkout can start immediately. Stop with Ctrl+C.

The login screen includes one-click **Researcher** and **Administrator** demo buttons. Manual credentials:

| Account | Email | Password |
| --- | --- | --- |
| Administrator | admin@aird.local | AdminDemo!2026 |
| Researcher · Customer intelligence | maya@aird.local | ResearchDemo!2026 |
| Researcher · Material discovery | jordan@aird.local | ResearchDemo!2026 |

These are local demo credentials. Demo mode binds to loopback by default. Production configuration disables demo seeding and requires a separately supplied administrator password.

Data persists in **data/platform.mv.db** and **data/files/** across restarts. Demo data is created only for an empty database. Existing workspace data is preserved on subsequent starts.

## What works

- Role-aware dashboards and server-enforced project permissions on every dataset, job, discussion, experiment, and download.
- Admin user creation/editing/deactivation, temporary passwords, last-admin protection, projects, membership, archives, resource registration, pool allocations, quotas, audit history, and usage exports.
- CSV validation, schema/missing-value summaries, escaped previews, metadata editing, immutable versions, protected downloads, deletion of unused versions, and archival of versions referenced by runs.
- Real Java logistic regression, random forest, majority baseline, and linear regression. Training-only preprocessing, seeded held-out splits, macro classification metrics, confusion matrices, and regression metrics.
- Persistent queued jobs, one CPU worker, real stage logs, duplicate-submission protection, transactional quota reservation, cooperative cancellation, leases, heartbeat, and interrupted-job reconciliation.
- Experiment groups, annotations/tags, compatible-run comparison, CSV summaries, JSON model artifacts, and project/experiment comments.
- Collaboration requests that grant access only after admin approval. Removed members lose access on their next request.
- Argon2 passwords, revocable server sessions, CSRF protection, login throttling, HttpOnly/SameSite cookies, CSP, escaped user content, and protected file storage.
- Responsive frontend served from the same Java application. No CDN, frontend package installation, external AI service, or paid API is needed.

The initial customer dataset is **synthetic and labeled as a demo fixture**, generated in Bootstrap.java with seed 42 (CC0). Its two seeded experiments are trained by the actual worker; scores are never pre-populated. Bring your own licensed CSV for real research.

## Build and test

~~~powershell
.\mvnw.cmd verify
node --test tests/frontend.test.cjs
~~~

Or run the combined script:

~~~powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check.ps1
~~~

The Maven wrapper activates an isolated output profile to avoid collisions with the editor's background Java compiler:

- Executable: **target/verified/ai-rd-platform-1.0.0.jar**
- Java test reports: **target/verified/surefire-reports/**

Start the packaged application directly:

~~~powershell
java -Xms128m -Xmx768m -jar target/verified/ai-rd-platform-1.0.0.jar --debug=false
~~~

For frontend/backend development:

~~~powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--debug=false"
~~~

Rebuild with verify after changing source before using start.cmd again. On Linux with curl, unzip, sha512sum, and Java installed: **sh mvnw verify**, then **java -jar target/verified/ai-rd-platform-1.0.0.jar --debug=false**.

## Try the full workflow

1. Sign in as Administrator. Open a project and add a researcher, or create a new project.
2. Sign in as Maya. Open Datasets, inspect the synthetic fixture, or upload your own CSV. Select a target with no missing labels.
3. Create an experiment. Choose a dataset version, model, seed, and test fraction. Open the run to see actual events, metrics, a confusion matrix, and its artifact.
4. Rerun with a second model, keeping dataset version, target, seed, and test fraction fixed. Select both completed runs and Compare.
5. Request Jordan as a collaborator. Approve as Administrator; Jordan can now read and comment on the shared project.
6. Review Usage & reports, filter results, and download CSV. Test an invalid CSV or unsupported classification target to see useful failure states.

## Configuration

| Environment variable | Default / meaning |
| --- | --- |
| PORT | 8080 |
| APP_BIND | 127.0.0.1 |
| DATABASE_URL | Local persistent H2 JDBC URL |
| DATABASE_USER / DATABASE_PASSWORD | sa / empty for local H2 |
| STORAGE_PATH | ./data/files |
| APP_MODE | all; api and worker modes support separate PostgreSQL-backed processes |
| DEMO_MODE | true locally; set false for a clean production database |
| ADMIN_EMAIL / ADMIN_PASSWORD | First non-demo initialization; password must be 16+ characters |
| COOKIE_SECURE | false for localhost HTTP; true behind HTTPS |
| CODE_COMMIT | Optional release commit recorded in artifacts |

Do not share one file-backed H2 database between separate processes. Use the supplied PostgreSQL deployment topology for separate API/worker processes. Sessions are kept in the single API process and are revoked by restart.

## Implementation and verification

| Layer | Implementation |
| --- | --- |
| Backend | Java 21 language target, Spring Boot 4.1.1, Spring MVC/Security/JDBC |
| Database | Persistent H2 locally; PostgreSQL JDBC support; versioned Flyway SQL migrations |
| Frontend | Lightweight HTML/CSS/JavaScript with hash navigation, shared forms/tables, inline SVG charts |
| ML | Bounded, inspectable Java reference estimators; no Python process |
| Queue | Transactional database queue with a singleton worker lease; no Redis/outbox needed |
| Delivery | Executable JAR, Windows/POSIX startup wrappers, Docker Compose/Caddy template, CI |

**Verified:** clean compile/package; 21 Java tests (including live HTTP integration, quota races, cancellation, worker loss, and database/artifact restoration); 17 frontend rendering checks; packaged application smoke test and restart persistence.

**Verification limits:** no browser was connected to the computer-use tool, so interactive browser flows, screenshots, and visual layout have not been verified. Docker/PostgreSQL/HTTPS deployment has not been executed here. See [docs/verification.md](docs/verification.md).

This is a working local research prototype. It is not a benchmarked production ML platform. Model-selection cross-validation, distributed API sessions, scheduled off-machine backups, container deployment verification, GPU/notebooks, arbitrary code execution, and cloud provisioning are outside this implementation.

Read [architecture](docs/architecture.md), [API guide](docs/api.md), [operations](docs/operations.md), and [verification](docs/verification.md).
