# Device Slot Permission Fix — 2026-09-05

## Problem fixed

Customer login/device registration previously queried `/deviceSlots` using a tenant-wide query:

- `tenantId == currentTenant`
- `status == ACTIVE`

Normal customer users are intentionally restricted to reading their own device slot, so Firestore cannot prove that every document returned by that tenant-wide query is readable. This can produce `PERMISSION_DENIED` during Access Verification.

## New allocation strategy

`MainActivity.kt` no longer performs a tenant-wide `/deviceSlots` read. It:

1. Resolves the tenant document.
2. Reads the exact `activeLicenseId`.
3. Reads the exact active license document.
4. Tries deterministic slot documents (`tenantId_1`, `tenantId_2`, ... up to the license device limit).
5. Uses an atomic batch write for the device + slot.
6. If a slot is occupied by another device, Firestore rejects that write without exposing the occupant; the client tries the next slot.
7. If another concurrent request registered the same device, the client rechecks only its own `/devices` document.

## Security change

A tenant Super Admin can no longer overwrite another user's ACTIVE slot merely because they can manage the tenant. Super Admin updates must preserve the existing slot UID; revocation still works because the existing UID is preserved while status changes to `REVOKED`.

A revoked slot can be reclaimed by an authenticated active tenant user for the same active license. This allows normal slot reuse after Force Logout without opening tenant-wide slot reads.

## Testing

After deploying `firestore.rules` and building a new APK, test:

- New Demo user first login.
- Existing Demo user re-login.
- New Paid Super Admin first login.
- Paid Admin/User first login.
- Device limit with multiple devices.
- Force Logout followed by new-device registration.
- Existing device re-login.
- Offline/network failure.

The expected result is no `Access verification failed` caused by a tenant-wide `/deviceSlots` query.
