# TorBox provider package removed

This package (`provider/torbox/`) has been deleted as part of the Step 4 pivot.

All files that were here — `TorBoxApi.kt`, `TorBoxRepositoryImpl.kt`, `MockTorBoxRepository.kt`,
`SourceRepositories.kt` — have been removed. The `TorBoxRepository` interface, `ProviderId` enum,
and `ProviderConnection`/`ProviderAccount` domain models have also been deleted.

The replacement is the addon layer in `provider/htmljson/` backed by `AddonRepository`
(Room table `addon_configuration`).

Do not add new files to this directory.
