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


## v12.2 Regression + Build Integrity Fix
- Paid customer first-owner onboarding now claims tenant and creates the first Super Admin atomically.
- First-user Firestore rule supports both PAID and DEMO customer accounts securely.
- Login Demo CTA always refreshes `/system/demoPolicy` and never uses the 3-day default for customer display.
- Android predictive/system Back callback is explicitly enabled; child onboarding flows use BackHandler.
- Paid license lookup no longer requires a Firestore composite index; customerEmail is queried and status/validity are verified locally.
- Licensing data errors are no longer silently swallowed on the Super Admin screen.
- Release workflow now refuses missing signing secrets and verifies the final APK signature with apksigner.
- Android versionCode 16 / versionName 1.0.3 for unambiguous APK testing.
