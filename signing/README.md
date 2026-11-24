Put your release key here (both files are git-ignored):

- `release.jks`
- `signing.properties` with `storeFile`, `storePassword`, `keyAlias`, `keyPassword`

CI reads the same values from the repository secrets `SIGNING_KEYSTORE_BASE64`,
`SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`.
Without a key, release builds fall back to the debug key.
