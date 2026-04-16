# Space MCP Server

[![CI](https://github.com/mchernyavsky/space-mcp-server/actions/workflows/ci.yml/badge.svg?branch=master)](https://github.com/mchernyavsky/space-mcp-server/actions/workflows/ci.yml)
[![Release](https://github.com/mchernyavsky/space-mcp-server/actions/workflows/release.yml/badge.svg)](https://github.com/mchernyavsky/space-mcp-server/actions/workflows/release.yml)
![Java 21](https://img.shields.io/badge/java-21-007396)
![Kotlin 2.3.20](https://img.shields.io/badge/kotlin-2.3.20-7F52FF)
![License: Apache-2.0](https://img.shields.io/badge/license-Apache%202.0-blue.svg)

Kotlin MCP server for JetBrains Space code reviews and merge requests.

## Requirements

- Java 21
- Access to a JetBrains Space instance such as `https://jetbrains.team`
- Either a Space OAuth application for `space_authorize` or a Space access token for env-based local auth

## What It Supports

- OAuth authorization code flow with PKCE against `https://jetbrains.team`
- Stored credentials with refresh-token support
- Listing visible Space projects
- Listing project reviews with public HTTP API filters:
  - `repository`
  - `state`
  - `type`
  - `text`
  - `author`
  - `reviewer`
  - `from`
  - `to`
- Aggregating "my reviews" across visible projects by scanning projects with `author=me` and `reviewer=me`
- Fetching a review with:
  - resolved review author / creator details when available
  - branch information
  - commits
  - review feed messages
  - flattened human-authored comments
  - code discussion threads and replies
- Listing human-authored review comments with optional author filtering
- Posting a plain review comment to the main review feed
- Creating a code discussion anchored to a file/line
- Replying to an existing discussion channel

## Quick Start

1. Get the runnable fat jar in one of these ways:

   - Download it from [GitHub Releases](https://github.com/mchernyavsky/space-mcp-server/releases).
   - Or build it from source:

     ```bash
     ./gradlew build
     ```

     `build` includes tests and ktlint checks.

   In both cases, the file you want is the fat jar:

   ```text
   build/libs/space-mcp-server-0.1.0-all.jar
   ```

2. Configure your MCP host to run that jar with `java -jar`:

   ```json
   {
     "mcpServers": {
       "Space": {
         "command": "java",
         "args": [
           "-jar",
           "/path/to/space-mcp-server/build/libs/space-mcp-server-0.1.0-all.jar"
         ]
       }
     }
   }
   ```

   If `java` is not Java 21 on your machine, use the absolute path to a Java 21 binary instead.

3. Authenticate:
   - set `SPACE_ACCESS_TOKEN` in the MCP host config for local non-interactive auth, or
   - call `space_authorize` once to complete OAuth.

4. Verify the integration with:
   - `space_auth_status`
   - `space_list_my_reviews`
   - `space_get_review`
   - `space_list_review_comments`

## Build

```bash
./gradlew build
```

The runnable fat jar is created at:

```text
build/libs/space-mcp-server-0.1.0-all.jar
```

## GitHub Workflows

- `CI` runs on every pull request and on pushes to `master` and `main`.
- The CI job runs `./gradlew build`, which includes compilation, tests, and ktlint checks.
- Every CI run uploads:
  - the runnable fat jar artifact
  - Gradle reports and test results
- `Release` runs on pushed tags matching `v*` and can also be started manually with a tag input from the Actions UI.
- Release runs build the server, publish a versioned fat jar plus `SHA256SUMS.txt` as workflow artifacts, and attach those files to the GitHub release.

## Run

If you downloaded the release jar, run that file. If you built from source, run the jar from `build/libs`.

```bash
java -jar build/libs/space-mcp-server-0.1.0-all.jar
```

The server uses stdio transport.

## Authorization

The server supports two auth modes:

- OAuth via the `space_authorize` MCP tool
- direct bearer-token bootstrap via environment variables for local `stdio` clients

### OAuth

Use the `space_authorize` MCP tool with:

- `clientId`
- optional `clientSecret`
- optional `serverUrl` (defaults to `https://jetbrains.team`)
- optional `scope` (defaults to `**`)
- optional `redirectUri`

Default redirect URI:

```text
http://localhost:63363/api/space/oauth/authorization_code
```

For a normal Space application, configure the exact same redirect URI in the Space app settings.

### Environment Variables

If your MCP host can pass environment variables to a local server process, you can skip the interactive OAuth tool and bootstrap auth directly with:

- `SPACE_ACCESS_TOKEN`
- optional `SPACE_SERVER_URL` (defaults to `https://jetbrains.team`)
- optional `SPACE_API_BASE_URL` (defaults to `<SPACE_SERVER_URL>/api/http`)
- optional `SPACE_SCOPE`
- optional `SPACE_CLIENT_ID`

`SPACE_ACCESS_TOKEN` also overrides the stored access token at runtime when OAuth credentials already exist.

Example local MCP config:

```json
{
  "mcpServers": {
    "Space": {
      "command": "java",
      "args": [
        "-jar",
        "/path/to/space-mcp-server/build/libs/space-mcp-server-0.1.0-all.jar"
      ],
      "env": {
        "SPACE_SERVER_URL": "https://jetbrains.team",
        "SPACE_ACCESS_TOKEN": "your-token"
      }
    }
  }
}
```

Stored credentials are written to:

- `$XDG_CONFIG_HOME/space-mcp-server/credentials.json`, or
- `~/.config/space-mcp-server/credentials.json`

## Common Tool Flow

Check the current auth state:

```json
{ "name": "space_auth_status", "arguments": {} }
```

Example response:

```json
{
  "configured": true,
  "authenticated": true,
  "serverUrl": "https://jetbrains.team",
  "currentUser": {
    "id": "3j5uoD3koYEa",
    "username": "Mikhail.Chernyavsky"
  }
}
```

List your reviews:

```json
{
  "name": "space_list_my_reviews",
  "arguments": {
    "projectKey": "FLEET",
    "role": "both",
    "limit": 20
  }
}
```

Example response:

```json
{
  "reviews": [
    {
      "projectKey": "FLEET",
      "matchedRoles": ["reviewer"],
      "review": {
        "number": 7705,
        "title": "[air] WIP AIR-4493 introduce tree-like changes structure",
        "resolvedAuthor": {
          "username": "Aleksandr.Chernokoz"
        }
      }
    }
  ],
  "scannedProjects": 20,
  "projectScanTruncated": true,
  "requestedLimit": 20
}
```

Fetch a review with commits and comments:

```json
{
  "name": "space_get_review",
  "arguments": {
    "projectKey": "FLEET",
    "review": "number:7705",
    "includeCommits": true,
    "includeComments": true
  }
}
```

Example response excerpt:

```json
{
  "review": {
    "number": 7705,
    "title": "[air] WIP AIR-4493 introduce tree-like changes structure",
    "resolvedAuthor": {
      "username": "Aleksandr.Chernokoz"
    }
  },
  "commits": [
    {
      "repositoryInReview": {
        "name": "ultimate"
      },
      "commits": [
        {
          "id": "9013469ed495a90305cb7b886b1b18c8548b09f7",
          "author": {
            "name": "Aleksandr Chernokoz"
          }
        }
      ]
    }
  ],
  "comments": {
    "entries": [
      {
        "kind": "code-discussion",
        "text": "Please rename this",
        "author": {
          "name": "Mikhail.Chernyavsky"
        }
      }
    ]
  }
}
```

Filter review comments by author:

```json
{
  "name": "space_list_review_comments",
  "arguments": {
    "projectKey": "FLEET",
    "review": "number:7705",
    "author": "Mikhail.Chernyavsky"
  }
}
```

Example response:

```json
{
  "authorFilter": "Mikhail.Chernyavsky",
  "count": 2,
  "comments": [
    {
      "kind": "review-feed",
      "text": "Looks good"
    },
    {
      "kind": "code-discussion",
      "anchor": {
        "filename": "src/Main.kt",
        "line": 42
      }
    }
  ]
}
```

## Reset Local Auth State

There is no dedicated logout tool.

To reset locally stored OAuth credentials, delete:

- `$XDG_CONFIG_HOME/space-mcp-server/credentials.json`, or
- `~/.config/space-mcp-server/credentials.json`

Environment-based auth can be reset by removing `SPACE_ACCESS_TOKEN` from the MCP host configuration.

## Exposed Tools

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

## Verified Space HTTP API Coverage

Verified from local Space sources and SDK references:

- Public review listing endpoint exists: `GET /projects/{project}/code-reviews`
- Public review details endpoint exists: `GET /projects/{project}/code-reviews/{reviewId}`
- Public review details-with-commits endpoint exists: `GET /projects/{project}/code-reviews/{reviewId}/details`
- Review comments are accessible through the review `feedChannelId` plus chat APIs
- Public code discussion creation endpoint exists: `POST /projects/{project}/code-reviews/code-discussions`
- Public chat message endpoints exist for discussion replies and main review-feed comments

## Known Limits

- A direct public "list my review participations" endpoint exists in source, but it is behind an internal HTTP API feature flag. This server does not depend on it.
- A public `submitPendingMessages(reviewId)` HTTP endpoint was not confirmed. The server can create pending messages/discussions, but reliable publication of pending drafts is not implemented.
- Cross-project "my reviews" is implemented by scanning visible projects and using public `author` and `reviewer` filters, not by a single dedicated public endpoint.

## License

This repository is licensed under Apache-2.0. See `LICENSE`.

For publication and reference-repository audit notes, see `PROVENANCE.md`.
