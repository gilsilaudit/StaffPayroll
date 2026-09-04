# Staff Payroll — Clean Merged Baseline

Version: 1.0.1

This project is merged from the user's current v0.3.3 GitHub baseline with the latest licensing, configurable demo policy, demo anti-reuse/re-demo, demo activation tracking, lead management, configurable lead statuses, and renewal lead architecture.

## Important
- Existing Firebase configuration (`app/google-services.json`) is preserved.
- Old global `set-super-admin.yml` workflow is removed.
- Developer access is provisioned through `provision-user-access.yml`.
- Firestore rules are replaced with the tenant/licensing/demo/lead security model.
- Customer business data remains outside this Firebase control plane.
- Customer-facing license keys are not required.
- Existing demo history is not deleted when Sales grants a re-demo.

## GitHub implementation
1. Keep the existing repository as backup/reference.
2. Replace the repository project files with this project's `StaffPayroll-main` contents.
3. Commit to `main`.
4. Let the Build Android APK workflow run.
5. After confirming the build, manually run `Deploy Firestore Rules`.
6. Provision the trusted Developer account using `Provision Developer Access`.

Do not separately apply the older Licensing-Implementation v1.0, Demo Policy v0.7.0, or v0.9.0 packages. This package is the merged baseline.
