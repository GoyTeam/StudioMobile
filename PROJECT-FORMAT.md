# Studio Mobile project format — schema 1

A **local** experience is a directory containing `project.yml`, `places/<uuid>.rbxlx` (or `.rbxl` for replacements), and `backups/`. The manifest is app-defined portable metadata, not a format Roblox uploads directly. The actual place file is the upload body.

- `name`, `description`, `creator`, `devices`: project planning metadata. Creator supports the current user only; no group attribution is fabricated.
- `start_place`: ID of the local default place, not a claim that a cloud root place was changed.
- `places[]`: name, ID, relative file path, template, planned server size, initial gravity/streaming, file SHA-256, optional cloud place ID and server-confirmed upload receipt.
- `cloud.universe_id`: verified existing target. One universe per local project.
- `last_attempt`: an upload attempt is recorded separately from `last_upload`. A lost response is **unconfirmed**, not success.

## What Create actually does

Creates a directory in `Documents/StudioMobile/Projects` or `Projects` within the selected SAF folder. Generates a real XML Roblox data model (`version="4"`). Baseplate creates Workspace, an anchored baseplate, SpawnLocation and service containers. Empty omits the two template parts. Gravity/streaming are serialized into Workspace. No game engine or 3D editor is pretended to exist.

## Supported management

Add local places; choose a local start place; rename; change experience metadata/device plan; replace a place file with an on-device backup; remove non-start places with backup; duplicate without cloud links; inspect file type/size/hash; export a project snapshot ZIP; import the exported snapshot with safe YAML parsing, checksums and path/size limits; delete a local project only after confirmation. Export is a snapshot of current places + manifest, not backup history.

ZIP imports clear cloud links and previous upload receipts to avoid accidentally overwriting another experience. No archive entry is extracted to an arbitrary path. Unknown/unsafe entries are rejected; compressed entries are bounded during reading. Schema 1 supports at most 100 places and a 300 MiB imported snapshot.

## Save / publish

Link each local place to an **existing** Universe ID + Place ID; the Get Place response must confirm that pair. No server operation happens merely because you created a local project or linked a target.

On explicit confirmation, sends the actual `.rbxlx` or `.rbxl` file to:

`POST https://apis.roblox.com/universes/v1/{universeId}/places/{placeId}/versions?versionType=Saved|Published`

Requires `universe-places:write` and the account's actual edit/publish eligibility. XML uses `application/xml`; binary uses `application/octet-stream`. Uploads do not auto-retry. Only a positive Roblox `versionNumber` is treated as confirmation. The receipt includes version/type/file hash/uploader/time, never the API key.

**Not applied by this upload:** experience name/description, devices, planned server size, cloud root-place selection, visibility, age/compliance settings or external asset dependencies. Configure these in Creator Dashboard / desktop Studio. Saved is not live; Published updates the selected place's version and does not make a private experience public.

A supported API-key universe-creation endpoint was not found in the checked official documentation. Create brand-new cloud experiences/additional cloud places in desktop Studio first, then link them. This app does not request Roblox passwords or session cookies to evade that limitation.

## Verification boundaries

Generated XML can be structurally parsed and hashed here. Desktop Roblox Studio loading/playtesting and a successful live upload require testing in Roblox's environment with an authorized account; no such success is fabricated in the QA screenshots.

Sources checked October 10, 2026:
- https://create.roblox.com/docs/production/publishing/publish-experiences-and-places
- https://create.roblox.com/docs/cloud/guides/usage-place-publishing
- https://create.roblox.com/docs/cloud/reference/features/places
- XML serialization reference (community reverse-engineering, not an official engine guarantee): https://github.com/RobloxAPI/spec/blob/master/formats/rbxlx.md
