# Provenance

This repository is intended to be published as an original implementation of a Kotlin MCP server for JetBrains Space.

## Reference Repositories Consulted

The following local repositories were consulted during development:

- `space-kotlin-sdk`
- `github-mcp-server`
- `ultimate`
- `space`

## License Context From References

- `space-kotlin-sdk` contains an Apache-2.0 license in its root `LICENSE` file.
- `github-mcp-server` contains an MIT license in its root `LICENSE` file.
- `ultimate/community` contains Apache-2.0 and a `NOTICE.txt`.
- The `ultimate` repository also contains non-community / proprietary material outside the Apache-licensed community subtree.
- A clear root open-source license was not identified in the local `space` checkout.

## Audit Summary

Before adding the publication license to this repository, the implementation was checked for obvious vendored code from the referenced repositories.

That audit included:

- direct diffs against the closest-looking reference implementation in `ultimate`
- exact-line overlap checks for nontrivial implementation lines
- a scan for copied file headers or license headers

Based on that audit, this repository appears to be an original implementation informed by reference material rather than a source vendoring of those repositories.

## Publishing Intent

This repository is licensed under Apache-2.0.

If future changes copy code directly from another repository, update the licensing and notice files accordingly before publishing.
