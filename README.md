# Staff Payroll — Milestone 2

Firebase foundation for the Staff Attendance & Payroll Management System.

Required before build:
`app/google-services.json`

Firebase Android package name:
`com.speqta.staffpayroll`

Milestone 2 adds Firebase configuration, Authentication and Firestore SDKs,
Firebase initialization verification, and deny-by-default Firestore rules.


## Demo activation v5
Core demo activation is atomic; audit/lead history is committed after core activation succeeds. Firestore errors now include diagnostic details.


## v11 UX fix
- Existing ACTIVE Demo accounts are opened as existing accounts, not treated as a second Demo activation.
- Device demo history continues to block genuinely new/re-demo attempts unless reset is granted.
