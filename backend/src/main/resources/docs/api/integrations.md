# Integrations API

## Endpoints

### Slack

#### GET /v1/integrations/slack/channels
List available Slack channels.

#### PUT /v1/integrations/slack/channel
Set the default alert channel.

#### PUT /v1/integrations/slack/toggle
Enable or disable Slack integration.

#### DELETE /v1/integrations/slack
Disconnect Slack. **Destructive - requires confirmation.**

#### POST /v1/integrations/slack/test
Send a test notification.

### Discord

#### GET /v1/integrations/discord/channels
List available Discord channels.

#### PUT /v1/integrations/discord/channel
Set the default alert channel.

#### PUT /v1/integrations/discord/toggle
Enable or disable Discord integration.

#### DELETE /v1/integrations/discord
Disconnect Discord. **Destructive - requires confirmation.**

#### POST /v1/integrations/discord/test
Send a test notification.
