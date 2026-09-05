Staff Payroll — v12.3

Fixes:
- Paid customer first-login licensing lookup now queries licenses by authenticated customerEmail, matching Firestore read rules.
- License is then validated locally for tenant, ACTIVE status and future validUntil.
- Preserves dynamic Demo Policy and Android Back/edge-swipe handling from v12.x.
- Version: 1.0.4 (versionCode 17).
