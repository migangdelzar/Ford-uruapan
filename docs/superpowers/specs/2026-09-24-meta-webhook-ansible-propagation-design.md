# Meta Webhook Ansible Propagation Design

## Goal

Make the Ansible deployment path propagate the complete Meta webhook runtime
configuration to `emme-1`, create the corresponding WhatsApp binding on
`emme-2`, and transfer locally built ARM64 images reliably.

## Current failure

The operator config contains empty Meta credentials, while the Ansible
inventory and application template only carry the app secret and verification
token. Automatic binding fields are omitted. The running application therefore
cannot verify signatures and the database has no enabled WhatsApp binding.
The local-build branch also marks the image as preloaded without transferring
it to `emme-1`.

## Design

- `deployment/config/oci.env.example` documents the Meta app secret,
  verification token, automatic-binding switch, provider account ID, and
  tenant UUID.
- `remote.sh` validates required Meta values, renders them into the private
  Ansible inventory, and never prints their values.
- `app.env.j2` renders the values into the mode-0600 application environment.
- The existing startup initializer creates or reconciles the WhatsApp binding
  using the provider phone-number ID and tenant UUID.
- `deploy:app` saves a locally built ARM64 image to the private deployment
  state directory and passes `app_image_archive` to Ansible. The existing
  remote-build fallback remains preloaded.
- The signature verifier treats a blank app secret as an invalid webhook
  rather than an internal state error.
- `deploy:secrets` is the single local secret-entry task: it generates missing
  deployment-only credentials and securely prompts for missing externally
  issued Meta, Grafana, and Langfuse credentials.
- `deploy:app` remains the only propagation step. It loads the completed
  ignored config, renders secrets into private Ansible inventory, and writes
  the remote application environment with mode `0600`.
- Ansible verifies application health and private database/Redis connectivity;
  a signed webhook smoke check is run only after real Meta values are present.

## Security constraints

Secrets remain in the ignored `deployment/config/oci.env` and private
`.mise/state` files. No secret values are logged, committed, or embedded in
the image. Database volumes are not reset and the data role is not run during
an app-only deployment.

The secret task never accepts secrets as command-line arguments. Missing
values are read without echo, written atomically to the ignored config, and
the task fails in non-interactive execution when a required externally issued
value is absent. Non-empty secret environment variables take precedence over
blank values in the ignored config, allowing CI or a secret manager to supply
them without command-line arguments.

## Verification

- Unit test blank and valid Meta signature behavior.
- Contract-test the Ansible propagation and image-archive arguments.
- Run focused Gradle tests and shell contract tests.
- Run `mise run deploy:check`, then `mise run deploy:app` after the operator
  has populated the real Meta values.
- Verify app health, verification GET, signed webhook POST, and the enabled
  WhatsApp binding count without printing secrets or payloads.
