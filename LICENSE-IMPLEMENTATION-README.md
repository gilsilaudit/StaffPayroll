# Staff Payroll — Licensing Implementation v1.0

This build supersedes the temporary M3.2-P3 identity flow. Do **not** run the old `Provision User Access` workflow from P3.

## Final customer flow

1. Developer/License Team creates the customer and issues an active license.
2. No customer-facing License Key is required.
3. The License Team registers the customer's login email, staff limit, device limit, modules and common validity.
4. Customer creates an account with that same email and password, or signs in if the account already exists.
5. The app finds the active license by the authenticated email.
6. Customer completes onboarding/profile and claims the unassigned tenant.
7. The first authorized customer account becomes `SUPER_ADMIN` and receives a tenant-scoped Staff ID.
8. The app registers a device and creates one active session for that account.
9. A Staff/Admin account may have only one active session/device at a time.
10. Customer Super Admin can remotely revoke active Staff/Admin sessions. Developer intervention is not required.

## License behavior

- Staff and device limits are separate entitlements.
- One active license has one common `validUntil` date for all entitlements.
- License Team can change staff/device limits and validity during the active period.
- Added devices/staff do not get separate expiry dates.
- Reducing a limit never deletes existing records.
- License history is append-only.
- Expired/suspended licenses are intended to block business-data writes while preserving existing data access; business-data collections remain closed in this milestone.

## Firestore system collections

- `/system/counters`
- `/tenants/{tenantId}`
- `/licenses/{licenseId}`
- `/licenseHistory/{historyId}`
- `/users/{uid}`
- `/devices/{deviceId}`
- `/sessions/{sessionId}`
- `/provisioning/{provisioningId}`
- `/auditLogs/{auditId}`

Customer business data is deliberately not stored in these Firebase collections.

## Security model

- Developer identity remains a trusted Firebase custom claim: `role=DEVELOPER`.
- Customer roles are stored in `/users/{uid}` and are tenant-scoped.
- First customer onboarding is a controlled Firestore claim of an `UNASSIGNED` tenant whose registered email matches the authenticated account.
- Cross-tenant access is denied by rules.
- The Android client cannot grant itself Developer access.
- The old P3 `Provision User Access` workflow that manually assigned `SUPER_ADMIN` has been removed.

## Deployment

1. Replace the project with this ZIP.
2. Commit/push to GitHub.
3. Run **Deploy Firestore Rules** from GitHub Actions.
4. Ensure the trusted Developer account still has the `DEVELOPER` custom claim.
5. Build the APK using the existing build workflow.

## Version

Application version: **0.5.0** (versionCode 7).

This is the licensing implementation milestone. Attendance, Leave, Salary and Payroll business collections remain intentionally closed.
