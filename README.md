# Space MCP Server

Kotlin MCP server for JetBrains Space code reviews and merge requests.

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
  - branch information
  - commits
  - review feed messages
  - code discussion threads and replies
- Posting a plain review comment to the main review feed
- Creating a code discussion anchored to a file/line
- Replying to an existing discussion channel

## Build

```bash
./gradlew build
./gradlew shadowJar
```

The runnable fat jar is created at:

```text
build/libs/space-mcp-server-0.1.0-all.jar
```

## Run

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
      "command": "/Users/Mikhail.Chernyavsky/Library/Java/JavaVirtualMachines/jbr-21.0.10/Contents/Home/bin/java",
      "args": [
        "-jar",
        "/Users/Mikhail.Chernyavsky/Workspaces/space-mcp-server/build/libs/space-mcp-server-0.1.0-all.jar"
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

## Exposed Tools

- `space_auth_status`
- `space_authorize`
- `space_list_projects`
- `space_list_reviews`
- `space_list_my_reviews`
- `space_get_review`
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
