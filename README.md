# POWERBILL – Slab-Based Utility Billing Engine

Academic Java console project, code CP-071. PowerBill stores consumer, reading, tariff, bill, payment, subsidy, and history records in MongoDB and calculates bills from versioned progressive tariff snapshots.

## Overview

The application demonstrates utility billing workflows: consumer registration, monthly meter readings, progressive slab charges, payment tracking, configurable demo subsidies, yearly consumption comparisons, unusual-consumption warnings, CSV input/output, and simulated demonstration data. It does not connect to BESCOM customer accounts or live provider billing systems.

## Objectives

- Demonstrate electricity billing through a database-backed Java 21 console application.
- Keep tariff calculations, meter rules, payment rules, and consumption analytics reusable and testable independently of console input.
- Preserve bills with their tariff snapshots and require an audited correction flow for issued bills.
- Label provider rates, subsidy schemes, and late-fee rules as demonstrations unless supported by official documentation.

## How the application works

The user registers a consumer, records a monthly meter reading, and generates a bill for that period. The billing engine applies progressive tariff slabs, fixed and additional charges, adjustments, and eligible subsidies. Each bill stores its tariff and calculation details. Payments require a unique receipt reference; late fees are applied once. A correction archives the prior bill, reading, and subsidy history before recalculation. On startup, incomplete prepared corrections are restored from those snapshots.

`Main` coordinates console menus and workflows. `MongoStore` owns the MongoDB client and indexes. `BillingEngine`, `MeterReadingCalculator`, `PaymentCalculator`, and `ConsumptionAnalytics` contain the isolated business rules. The project uses a compact package, rather than separate model, repository, service, and menu packages; MongoDB `Document` objects are used at the persistence boundary.

**Tariffs:** on an empty tariff collection, the application inserts a starter tariff labelled `DEMO` for BESCOM. Its amounts are invented solely for software demonstration and are not official rates. Add verified provider rates only after consulting and recording the relevant official tariff order from [KERC](https://kerc.karnataka.gov.in/) or the provider. Each bill stores the tariff document used at generation time.

**Due dates and late fees:** bills are due 20 days after generation by default; configure this with `POWERBILL_DUE_DAYS`. The demonstration workflow applies an example late-fee rule of 2% of outstanding amount, minimum ₹10 and maximum ₹500, once when an overdue bill is reviewed or paid. Configure the demo values with `POWERBILL_DEMO_LATE_FEE_RATE`, `POWERBILL_DEMO_LATE_FEE_MIN`, and `POWERBILL_DEMO_LATE_FEE_MAX`. These are not official provider policies.

## Technology and requirements

- Java 21
- Apache Maven 3.10 or compatible
- MongoDB Community Server (standalone is supported; the application does not use multi-document transactions)
- MongoDB Java Sync Driver 5.2.1
- JUnit 5.11.4

Runtime configuration is loaded from `src/main/resources/application.properties` when present, with `POWERBILL_MONGODB_URI` and `POWERBILL_DATABASE` environment variables taking precedence. Defaults are `mongodb://127.0.0.1:27017` and `powerbill_db`. Copy [application.properties.example](application.properties.example) to that resource path for local settings; the real configuration file is ignored by Git. No credentials are stored in the project.

## Build and run

Install Java 21, Maven 3.10 or compatible, and MongoDB Community Server. Start the MongoDB service and verify that the local port is reachable. Then, in PowerShell from the project directory:

```powershell
mvn clean test
mvn package
java -jar target/powerbill-1.0.0.jar
```

`mvn package` produces a self-contained executable jar under `target/`. Maven downloads the pinned dependencies on the first build. Ensure MongoDB is running before launch. The application pings MongoDB and creates indexes at startup; if MongoDB is unavailable, it exits with a connection message. Run with `POWERBILL_MONGODB_URI` and `POWERBILL_DATABASE` set when using non-default values.

## Features

- Consumer registration, listing/search, update, deactivation, and bill history.
- Monthly meter readings with duplicate period protection and retained history. Replacement readings capture the old meter's final value; rollover calculations require `POWERBILL_METER_MAX_READING` (the inclusive highest displayed value, with decimal scale setting register resolution) to be configured.
- Progressive tariff slabs, fixed and additional charges, signed adjustments, subsidies, currency rounding, due dates, and a detailed bill containing a tariff snapshot. Duplicate bills are prevented by a unique consumer/period index.
- Controlled correction of unpaid issued bills: requires a reason and explicit `CORRECT` confirmation, archives the prior bill and reading, increments the bill version, recalculates against the bill's original tariff snapshot, and records audit events. Bills with payments or applied late fees are blocked from correction. An interrupted prepared correction is restored from its snapshots when the application next starts.
- Effective dated tariff versions. Rate changes are added as a new version; retiring a tariff sets its last effective day and preserves a before snapshot and reason in retirement history. Existing bills retain their tariff snapshots.
- Full or partial payments and outstanding balance. Every payment requires a unique receipt/reference value enforced by a sparse unique MongoDB index. A late fee is applied at most once per bill.
- Same-month yearly comparison and percentage-change calculations; safe missing and zero baseline handling.
- Demonstration subsidy schemes with category, unit cap, amount cap, effective dates, and active state.
- Configurable spike threshold (default 50%) and warning language that describes a pattern requiring investigation.
- Mongo-backed report views, CSV import/export for consumers, meter readings, tariffs, bills, and consumption history, plus row-level import error reports and simulated data generation.
- Simulated data is explicitly named and tagged, with 30 consumers and 24 months of readings each across two completed years; generation checks for existing sample consumers. Patterns include stable, increasing, decreasing, seasonal, and spike examples.

## Architecture and structure

```text
src/main/java/com/powerbill/
  Main.java                 Console menus and workflows
  MongoStore.java            Mongo client lifecycle and indexes
  BillingEngine.java         Independent BigDecimal slab calculation
  MeterReadingCalculator.java Reading, replacement, and rollover rules
  PaymentCalculator.java      Balance and late-fee rules
  ConsumptionAnalytics.java   Year comparison and spike rules
src/test/java/com/powerbill/
  *Test.java                  Billing, meter, payment, analytics, and Mongo tests
```

The console uses `Document` records at the MongoDB boundary. `BillingEngine` is isolated from persistence and uses `BigDecimal` with `HALF_UP` rounding to two currency decimals.

## Database collections

`consumers`, `electricity_providers`, `tariffs`, `meter_readings`, `bills`, `payments`, `application_counters`, `subsidies`, `subsidy_history`, `consumption_history`, `bill_corrections`, `meter_reading_corrections`, `tariff_retirements`, and `audit_logs` are used. Unique indexes protect consumer IDs, provider IDs, tariff IDs, scheme IDs, consumer/month readings and history, consumer/month bills, payment IDs, receipt IDs, and correction IDs. Payment receipt numbers are allocated atomically from the persistent `application_counters` collection; the sparse legacy payment-reference index remains for older payment records. The first application run inserts the five named Karnataka providers and a pair of clearly labelled demo BESCOM tariffs only when missing. MongoDB index creation can fail at startup if pre-existing duplicate data violates a new unique index.

## CSV formats

- Consumer export: `consumerId,name,address,phone,category,provider,status`.
- Meter-reading import: `consumerId,period,previous,current`, with period `YYYY-MM`; the full six-column reading export can also be imported.
- Historical-consumption import: `consumerId,period,units,note`. Report CSV exports include consumer, bill, overdue bill, payment, consumption, subsidy scheme, billing summary, status-filtered bill, and applied-subsidy-history reports.

Imports check record shape, required fields, configured providers, consumer status/category, non-overlapping tariff effective ranges, period, and meter/tariff values, and report each invalid row. Duplicate tariff versions and consumer/month history rows are rejected. Tariff CSV stores slab configuration as JSON in the `slabsJson` column along with signed adjustments. Imported readings update both meter records and consumption history; standalone history CSV rows are supported too.

## Tests

Run `mvn clean test` and `mvn package`. The JUnit suite includes calculation and business-rule tests, a MongoDB driver ping and CRUD test, and console-to-Mongo end-to-end workflows. The E2E suite uses randomly named `powerbill_e2e_*` databases and drops only those databases after each test. It covers consumer register/list/search/update/deactivate/history, normal/replacement/rollover readings, bill generation/correction/recovery, tariff retirement/version selection, subsidy flows, partial/full payments and overdue fees, comparisons and spike detection, reports, CSV actions offered by the menu, simulated data idempotency, and reconnecting after closing/reopening the store. The suite needs a reachable MongoDB server; it never selects the default `powerbill_db` database.

For a manual demo without writing to the normal database, set an isolated database before launch:

```powershell
$env:POWERBILL_MONGODB_URI = 'mongodb://127.0.0.1:27017'
$env:POWERBILL_DATABASE = 'powerbill_demo_validation'
java -jar target/powerbill-1.0.0.jar
```

Menu `0` exits. Menu `3` contains generate, correct, and view bill actions. Menu `9` contains tariff list, add version, provider list/configure, retire, and retirement history. Menu `11` lists supported CSV actions and expected headers.

### Demonstration workflow

Use a separate database name as shown above. Register a consumer (menu `1`, then `1`), record a reading (menu `2`, then `1`), and generate its bill (menu `3`, then `1`). Record a full or partial payment under menu `4`, supplying a unique receipt/reference value. Menu `10` opens reports, menu `12` loads simulated sample data, and menu `13` displays record counts. An empty database receives clearly labelled demo tariffs; these are not official provider rates.

## Limitations and future work

- The CSV parser supports quoted commas and escaped quotes but is line-oriented and does not support embedded newlines in fields. Current CSV imports cover consumers, meter readings, tariffs, and consumption history. The menu exports those records plus reports for payments, subsidies, bills, overdue bills, billing summaries, and applied subsidy history. Providers and correction archives do not have CSV actions.
- Sample readings populate the two most recent completed calendar years, allowing same-month comparison and spike examples immediately after generation.
- Provider tariff rates, late-payment policies, and government subsidies are not verified or represented as official. Obtain and cite current authoritative documents before configuring real values.
- No multi-document transactions are used. Prepared bill corrections are rolled back from their snapshots at the next startup, but a crash during payment insertion/status update or tariff retirement may require manual reconciliation. Audit records support review but are not an immutable ledger.
- Consumer deletion is implemented as deactivation to preserve billing records.

## Git status

The workspace used to prepare this project was not a Git checkout at the time of implementation. No repository was initialized, and no commit or push was performed. To work with the supplied GitHub repository, first open/clone its existing checkout, then review and apply these project files there.
