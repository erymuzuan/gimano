# Vendored libraries (self-hosted; no third-party CDN)

| File | Package | Version | Licence | npm tarball SHA-256 |
|---|---|---|---|---|
| `libsodium.mjs` | `libsodium` (dist/modules-esm) | 0.8.4 | ISC (`LICENSE.libsodium.txt`) | `ff817a39d4664e2aef5b15fcbe729e9a4060d931929b03af624cc8a5588625eb` |
| `libsodium-wrappers.mjs` | `libsodium-wrappers` (dist/modules-esm) | 0.8.4 | ISC (`LICENSE.libsodium.txt`) | `858ad2910255d9b6c3527d6b7e8bcfcd98917a495ad2c97775bf2bfede13dfaf` |
| `maplibre-gl.js`, `maplibre-gl.css` | `maplibre-gl` (dist) | 5.24.0 | BSD-3-Clause (`LICENSE.maplibre-gl.txt`) | `5cbf83c328c9d39cb24e3f2cefc9b407d69dd104ba0b41e84c0bfbb31c44e283` |

One local patch: in `libsodium-wrappers.mjs`, the bare import `from"libsodium"` is rewritten to
`from"./libsodium.mjs"`, so the file loads in browsers without an import map and in Node without
`node_modules`:

```bash
sed 's/from"libsodium"/from".\/libsodium.mjs"/' package/dist/modules-esm/libsodium-wrappers.mjs > libsodium-wrappers.mjs
```

To update, download the tarballs from registry.npmjs.org, verify them, copy the same files, re-apply the
patch and update this table.
