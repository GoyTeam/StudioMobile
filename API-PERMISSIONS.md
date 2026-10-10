# API permission matrix · checked against Roblox documentation 2026-10-10

This guide separates **required app operations** from **optional future capabilities**. API-key scopes cannot replace the account's ownership/edit permissions or make this app a full Roblox engine.

## Required for current connection + authorized download workflow

| Feature | API system / operation | Exact scope | Notes |
|---|---|---|---|
| Authorized place-content download | `legacy-asset` / **Manage** | `legacy-asset:manage` | Broad Manage permission. Requires actual asset/place access; not a made-up read-only scope. |
| Validate key and identify owner | Key introspection | No additional feature scope documented | POST `/api-keys/v1/introspect`; requires an active user-owned key. |
| Public profile, avatar, public games | Public Users/Thumbnails/Games endpoints used by this app | No additional API-key scope | Public results do not include every private/group experience. |
| Existing universe metadata | Open Cloud Get Universe | No extra named scope listed in the official GET reference | Authenticated accessible-ID lookup; access failures still need investigation. Do not invent `universe:read`. |

Download source: https://devforum.roblox.com/t/creator-action-required-new-asset-delivery-api-endpoints-for-community-tools/3574403
Key source: https://create.roblox.com/docs/cloud/auth/api-keys
Universe source: https://create.roblox.com/docs/cloud/reference/features/universes

## Save / publish is now implemented in 0.3.0

For saving or publishing a version to a verified existing target, select **universe-places / Write** for that experience: **universe-places:write**. This is required only when you use Save / Publish; creating/exporting a local project needs no cloud permission. It does not change visibility or create a universe.

## Other scopes by feature

Except for the implemented place-version Save / Publish workflow, these scopes document future capabilities, not implemented editor features. Do not enable a write scope unless the feature and trust model require it.

| Future feature | System / operation | Exact scope(s) | Caveat |
|---|---|---|---|
| Publish a version of an existing `.rbxl` / `.rbxlx` place | `universe-places` / **Write** | `universe-places:write` | Select target experience. Needs Universe ID, Place ID and publish/edit access. Not the same as place metadata Write. |
| Asset metadata, versions and operation results | `asset` / **Read** | `asset:read` | Not a substitute for Asset Delivery Manage. |
| Upload/update supported asset types, then poll operations | `asset` / **Read + Write** | `asset:read`, `asset:write` | Supported asset types and ownership restrictions apply. |
| Read supported engine instances / child instances (Beta) | `universe.place.instance` / **Read** | `universe.place.instance:read` | Limited beta API, not a complete Studio engine. |
| Read-then-edit supported instance properties (Beta) | `universe.place.instance` / **Read + Write** | `universe.place.instance:read`, `universe.place.instance:write` | PATCH needs Write; Read is included for the read/edit workflow. |
| Update supported place metadata | `universe.place` / **Write** | `universe.place:write` | Can conflict with active Team Create (HTTP 409). Does not publish a binary place version. |
| Read place history / contributors (Experimental) | `universe.place` / **Read** | `universe.place:read` | Documented for history endpoints; eligibility may vary. Not a universal private-experience discovery scope. |
| Experience settings or server restart | `universe` / **Write** | `universe:write` | Broad live-experience effect; isolate from a download-only key. |
| Authenticated Creator Store search/details | `creator-store-product` / **Read** | `creator-store-product:read` | Does not confer ownership or redistribution rights. |
| Future OAuth account identity | Registered OAuth application / PKCE | `openid`, `profile` | OAuth-only, **not API-key permissions**. Requires registered client/redirect configuration; never embed a client secret. |

Official publishing setup: https://create.roblox.com/docs/cloud/guides/usage-place-publishing
Official assets reference: https://create.roblox.com/docs/cloud/reference/features/assets
Official places/instances/history reference: https://create.roblox.com/docs/cloud/reference/features/places
Official universe settings reference: https://create.roblox.com/docs/cloud/reference/features/universes
Official OAuth setup: https://create.roblox.com/docs/cloud/auth/oauth2-overview

## Exact dashboard flow

1. Creator Dashboard → **Credentials → API Keys → Create API Key**. Use a user-owned key.
2. Name it Studio Mobile.
3. **Access Permissions → Select API System**. Select the API system associated with the feature in the matrix; dashboard display labels can differ from machine identifiers.
4. Choose the listed operation. For downloads, select the legacy Asset Delivery system (`legacy-asset`) and **Manage**.
5. Select intended experiences where that scope supports restrictions. Not every scope supports resource restrictions.
6. Optional security settings: expiration and IP restrictions. Mobile IP addresses change; do not relax rules blindly to work around access errors.
7. **Save & Generate key**. Copy the secret securely once, confirm Active status and paste directly into this app.
8. Roblox can auto-expire unused keys after 60 days. Treat the secret like a password. Logout clears the device credential, not the server-side key; revoke it separately in Creator Dashboard.

The optional list covers plausible editor/asset/publishing features, not every unrelated Roblox API. Datastore writes, moderation, secrets and commerce are not blanket prerequisites for a game editor. No scope unlocks all of Studio or bypasses Roblox permission enforcement.
