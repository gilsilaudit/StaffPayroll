# Paid Customer Activation — v1.1.1

## Final customer flow
1. Developer/Sales issues a PAID license against the customer's registered email.
2. Customer opens the app and taps `Activate Purchased License`.
3. Customer enters the registered email and creates their own password.
4. Firebase sends an email verification link.
5. Customer verifies the email and taps `I Have Verified My Email`.
6. App finds the active PAID license for that email.
7. App atomically claims the unassigned tenant and creates the customer's first `SUPER_ADMIN` user.
8. App returns to Sign In; customer signs in with their own email/password.
9. Existing device/session licensing then applies normally.

## Security
- Developer/Sales never sets or sees the customer's password.
- Customer cannot claim a tenant whose account type is DEMO.
- Customer can claim only an ACTIVE, unassigned PAID tenant whose primary email matches the verified Firebase email.
- Tenant ownership and first Super Admin creation are performed in one Firestore transaction.
- Firestore rules validate the paid onboarding path.
- Existing demo flow remains separate and unchanged.

## Version
- versionCode: 17
- versionName: 1.1.1


## v1.1.1 Fixes

- Paid activation now discovers the unactivated paid tenant by the registered customer email, then reads the single linked license document. This avoids the previous license-collection query path that could return a Firestore permission error.
- Login screen now reads `/system/demoPolicy` and displays the live Developer-configured demo duration instead of a hard-coded 3-day label.
- Android system Back gesture/button is restored for authentication sub-screens and Developer child screens.
- Customer license/tenant reads now require verified email when performed by the customer.
