# Meta webhook routing design

## Goal

Expose one stable callback per Meta channel and resolve the tenant and
connected number/page/account from the verified provider payload. The public
URL must not contain a tenant identifier, access key, or secret.

## Public contract

```text
GET  /webhooks/meta/{channel}
POST /webhooks/meta/{channel}
```

Meta uses the same verification token for the subscription handshake. POST
requests are authenticated with the Meta app secret and
`X-Hub-Signature-256` before the payload is trusted.

## Routing model

The application extracts a channel-specific provider account ID:

| Channel | Provider account ID |
| --- | --- |
| WhatsApp | `entry[0].changes[0].value.metadata.phone_number_id` |
| Messenger | `entry[0].id` (Page ID) |
| Instagram | `entry[0].id` (Instagram account ID) |

`(channel, provider_account_id)` is a unique database binding to exactly one
tenant. The binding is looked up only after signature verification. This
supports many tenants and numbers behind the same Meta app and callback URL.

The inbound Meta app secret is deployment-level configuration because Meta
signs callbacks with the app secret, not with a different secret per phone
number. The current outbound MVP still configures one endpoint/token per
channel; the binding's secret-reference field is reserved for the next
credential-resolver step if different Page/number tokens are required.

## Operational recommendation

Meta needs a publicly reachable HTTPS endpoint. An OCI Always Free VM can
serve it directly with a reserved public IP, inbound TCP 443, and a hardened
reverse proxy. For the preferred production shape, keep the service on OCI and
put Cloudflare in front: either proxied DNS to the public IP or a Cloudflare
Tunnel from the VM. A Tunnel is preferable when avoiding inbound ports or when
the VM should stay private; direct OCI ingress is simpler for an initial MVP.

## Non-goals

- Separate callback URLs per tenant or phone number.
- Storing Meta app secrets in webhook URLs.
- Creating one Meta app for every tenant.
- Treating a client-supplied tenant header as a routing authority.
