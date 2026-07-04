# Project: s-clinic

**Path**: `C:/Chung/s-clinic`
**Stack**: Java 17, Spring Boot 3.2.5, PostgreSQL, Flyway, Spring Security, JPA, Lombok, MapStruct
**Type**: Real product — small clinic management.
**Specialty**: Dermatology (da liễu), multi-doctor, designed to expand to other specialties.

## Modules (planned)
Patient, Staff/Doctor + roles, Appointment (multi-doctor scheduling),
Encounter (visit), Clinical photos (dermatology), Treatment course (multi-session),
Prescription, Invoice/billing, Audit log.

## Design notes
- Neutral core (patient/appointment/encounter/invoice) + specialty-specific data kept
  flexible via `encounter.clinical_data` (jsonb), so adding a new specialty needs no schema change.
- `specialty` + `service` tables make the catalog configurable per specialty.
- Schema owned by **Flyway** (`src/main/resources/db/migration`); JPA `ddl-auto=validate`.

## Security / compliance (sensitive medical data)
- Access control enforced at app layer (Spring Security roles: DOCTOR / RECEPTIONIST / ADMIN).
- `audit_log` tracks view/change of sensitive records.
- Clinical photos stored by `storage_key` in PRIVATE object storage, never public URLs.
- Vietnam: handle per Nghị định 13/2023 (personal/health data). Backups + HTTPS required.

## Run locally
1. Start PostgreSQL, create DB + user:
   ```sql
   create database sclinic;
   create user sclinic with password 'sclinic';
   grant all privileges on database sclinic to sclinic;
   ```
2. Env (override defaults in `application.yml`): `DB_URL`, `DB_USER`, `DB_PASSWORD`.
3. Run the app — pick one:
   - **Dedicated terminal** (simplest): `mvn spring-boot:run` in a separate terminal/tab,
     or use IntelliJ's Run button. Logs stay there; your other terminal is free.
   - **Detached background** (console returns immediately):
     ```powershell
     mvn -DskipTests package      # build the jar once
     .\scripts\run.ps1            # start in background, logs -> target\run.log
     Get-Content target\run.log -Wait   # tail logs when needed
     .\scripts\stop.ps1           # stop it
     ```
   Flyway applies `V1`/`V2` on startup. Default login: `admin` / `admin`
   (created on first boot — change it).

## Future integrations (designed-in via V2)
Planned external systems: **e-invoice** (HĐĐT, NĐ 123/2020 + TT 78),
**e-prescription** (đơn thuốc điện tử quốc gia), **EMR/bệnh án điện tử** (TT 46/2018, HL7 FHIR).

Extensibility mechanism (no vendor lock-in):
- Standard identifiers/codes added in `V2`: patient `national_id` (CCCD), `insurance_no` (BHYT),
  `tax_code`; encounter `diagnosis_code` (ICD-10).
- Per-record sync state: `invoice.einvoice_*`, `prescription.national_rx_code` + `sync_status`.
- **Outbox pattern** (`integration_outbox`): domain events written in the same transaction as the
  business change; a worker pushes them to a provider **adapter** (VNPT/Viettel/MISA/FHIR endpoint),
  marks SENT/FAILED. Keeps the core domain decoupled from external availability.
- `external_reference`: maps our IDs to external system IDs.

When implementing, follow ports-and-adapters: define an interface per target system
(`EInvoiceProvider`, `EPrescriptionClient`, `EmrSyncClient`) and select the concrete adapter by config.

## Status / next steps
- [x] Project skeleton + schema (V1)
- [x] Integration-readiness schema (V2)
- [ ] JPA entities + repositories
- [ ] Spring Security config (login, roles, password hashing)
- [ ] Patient CRUD (REST) first vertical slice
- [ ] Appointment scheduling, encounter, photos upload, billing
