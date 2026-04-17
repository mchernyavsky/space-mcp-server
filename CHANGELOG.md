# Changelog

All notable changes to this project will be documented in this file.

## 0.2.0 - 2026-04-17

- Added normalized review file/change listing through `space_list_review_changes`
- Added optional changed-file loading to `space_get_review`
- Removed obsolete raw HTTP fallback and extra SDK indirection layers in favor of direct SDK-backed review operations
- Replaced work-specific examples in docs and tests with fictional fixtures

## 0.1.0 - 2026-04-16

- Initial public release of the Space MCP server
- OAuth authorization code flow with PKCE and env-based token bootstrap
- Review listing, details, commits, branches, feed messages, and code discussions
- Review comment posting and discussion replies
- Comment filtering by author
- Java 21 build, Gradle 9.4.1 wrapper, GitHub Actions CI, and ktlint-based linting
