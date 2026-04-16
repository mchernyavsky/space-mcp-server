# AGENTS.md

## Scope

These instructions apply to the entire `space-mcp-server` repository.

## Goal

This repository contains a Kotlin MCP server for JetBrains Space reviews and merge requests.

Keep the implementation focused on:

- OAuth authorization against Space organizations such as `https://jetbrains.team`
- Read access to projects, merge requests, reviews, commits, branches, feed comments, and code discussions
- Commenting support through public Space HTTP APIs when available
- MCP stdio server behavior

## Repo Boundaries

- Do not edit code outside this repository.
- Treat `space`, `ultimate`, `space-kotlin-sdk`, and `github-mcp-server` as reference sources only.
- Do not commit generated `build/`, `.gradle/`, `.idea/`, or `.kotlin/` content.

## Project Layout

- `src/main/kotlin/team/jetbrains/space/mcp/Main.kt`
  - JVM entry point
- `src/main/kotlin/team/jetbrains/space/mcp/SpaceMcpServer.kt`
  - MCP server wiring and tool registration
- `src/main/kotlin/team/jetbrains/space/mcp/space/SpaceAuthService.kt`
  - OAuth code flow, PKCE, token exchange, refresh
- `src/main/kotlin/team/jetbrains/space/mcp/space/SpaceApiClient.kt`
  - Raw Space HTTP client and review/chat operations
- `src/main/kotlin/team/jetbrains/space/mcp/space/Models.kt`
  - DTOs for MCP responses and Space payloads
- `src/main/kotlin/team/jetbrains/space/mcp/space/SpaceCredentialStore.kt`
  - Local credential persistence

## Build And Validation

- Use Java 21.
- Preferred validation command:

```bash
./gradlew build
```

- Fat jar output:

```text
build/libs/space-mcp-server-0.1.0-all.jar
```

When changing build logic, auth flow, MCP registration, or DTOs, run `./gradlew build` before finishing.

## Authorization Assumptions

- Default server URL is `https://jetbrains.team`.
- Default OAuth scope is `**`.
- Default redirect URI is:

```text
http://localhost:63363/api/space/oauth/authorization_code
```

- For a regular Space application, the exact redirect URI must be configured in the Space app settings.
- Local `stdio` clients can also bootstrap auth without OAuth by setting:
  - `SPACE_ACCESS_TOKEN`
  - optional `SPACE_SERVER_URL`
  - optional `SPACE_API_BASE_URL`
  - optional `SPACE_SCOPE`
  - optional `SPACE_CLIENT_ID`
- Credentials are stored under:
  - `$XDG_CONFIG_HOME/space-mcp-server/credentials.json`, or
  - `~/.config/space-mcp-server/credentials.json`
- `SPACE_ACCESS_TOKEN` overrides the stored access token at runtime and now also works without a pre-existing credentials file.

## Current MCP Tools

- `space_auth_status`
- `space_authorize`
- `space_list_projects`
- `space_list_reviews`
- `space_list_my_reviews`
- `space_get_review`
- `space_list_review_comments`
- `space_post_review_comment`
- `space_create_code_discussion`
- `space_reply_to_discussion`

Keep tool names stable unless there is a strong reason to break compatibility.

## Space API Constraints Already Verified

- Public review listing exists via `GET /projects/{project}/code-reviews`.
- Public review details exist via `GET /projects/{project}/code-reviews/{reviewId}`.
- Review commits are available via `GET /projects/{project}/code-reviews/{reviewId}/details`.
- Review comments are retrieved through the review `feedChannelId` and chat APIs.
- Plain review-feed comments can carry author information directly on the chat message, while code-discussion roots and replies come through discussion channels. Keep both shapes wired.
- Code discussion creation is supported via `POST /projects/{project}/code-reviews/code-discussions`.
- Plain replies/comments are supported through chat message endpoints.

## Known Limits

- `listMyReviewParticipations` exists in Space sources but is behind an internal HTTP API feature flag. Do not depend on it.
- A public `submitPendingMessages(reviewId)` HTTP endpoint was not confirmed. Do not assume pending comments can be published automatically.
- Cross-project "my reviews" is implemented by scanning visible projects with public `author=me` and `reviewer=me` filters.

## Coding Notes

- Prefer raw Ktor HTTP calls unless the Space Kotlin SDK clearly reduces complexity without losing endpoint coverage.
- Keep DTOs permissive: `ignoreUnknownKeys = true` is intentional because Space payloads are broad and can evolve.
- Preserve backward compatibility for MCP tool outputs when possible.
- If you add new tools, also update `README.md`.
- If you change auth behavior or redirect handling, update both `README.md` and this file.

## Reference Workflow

When implementing new Space capabilities:

1. Check whether the endpoint is actually public in local Space sources.
2. Prefer project-scoped public APIs over internal feature-flagged endpoints.
3. Add or extend DTOs in `Models.kt`.
4. Add raw client support in `SpaceApiClient.kt`.
5. Expose the capability in `SpaceMcpServer.kt`.
6. Run `./gradlew build`.
