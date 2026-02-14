# Embedded Logs Component

The `EmbeddedLogs` component provides a reusable way to display logs with contextual filtering. It's designed to be embedded in other pages, such as the issue detail page.

## Features

- **Time-based context**: Show logs around a specific timestamp (e.g., ±5 minutes around an error)
- **Flexible filtering**: Filter by service, environment, log levels, tags, and search query
- **Compact or full mode**: Customize header visibility and height
- **Pagination**: Navigate through log results
- **Detail view**: Click any log to see full details in a slide-over panel

---

# Log Explorer URL State & Deep Linking

The Log Explorer on the project logs page (`/projects/:id/logs`) supports **URL-based state persistence** for deep linking and sharing.

## Shareable State

All viewer state is encoded in URL query parameters, enabling:

- **Share links**: Copy browser URL to share exact view with teammates
- **Browser history**: Back/forward navigation preserves filter/query state
- **Bookmarks**: Save frequently-used log views
- **Context navigation**: Jump to time windows around specific logs

### URL Parameters

| Param | Type | Description | Example |
|-------|------|-------------|---------|
| `q` | `string` | Search query | `?q=error+timeout` |
| `levels` | `string` | Comma-separated log levels | `?levels=error,warn` |
| `facets` | `string` | JSON-encoded facet filters | `?facets=[{"key":"service","value":"api"}]` |
| `timePreset` | `string` | Time range preset | `?timePreset=1h` |
| `from` | `string` | Custom range start (ISO) | `?from=2024-01-15T10:00:00Z` |
| `to` | `string` | Custom range end (ISO) | `?to=2024-01-15T11:00:00Z` |
| `viz` | `string` | Visualization mode | `?viz=table` |
| `groupBy` | `string` | Group by field (table viz) | `?groupBy=service` |
| `topField` | `string` | Top field (toplist/pie) | `?topField=environment` |
| `cursor` | `string` | Pagination cursor | `?cursor=abc123` |
| `logId` | `string` | Selected log ID | `?logId=xyz789` |

### Example URLs

**Error logs from last hour:**
```
/projects/42/logs?levels=error,fatal&timePreset=1h
```

**Service filter with custom time range:**
```
/projects/42/logs?facets=[{"key":"service","value":"api"}]&from=2024-01-15T10:00:00Z&to=2024-01-15T11:00:00Z
```

**Table view grouped by environment:**
```
/projects/42/logs?viz=table&groupBy=environment
```

## View in Context Action

The **View in Context** button in the log detail panel provides one-click navigation to a time-based context window around a selected log.

### Behavior

1. **Clears active filters**: Removes search query, facet filters, and level selection
2. **Sets ±5 minute window**: Creates custom time range centered on log timestamp
3. **Resets pagination**: Starts at first page of results
4. **Preserves log selection**: Selected log remains highlighted in table
5. **Updates URL**: Shares context view state via URL parameters

### Use Cases

- **Investigate surrounding activity**: See what happened before/after an error
- **Full context view**: Remove filters that might hide related logs
- **Share incident timeline**: Send URL showing exact time window to teammates

### Technical Notes

- Action only available when `enableUrlSync` prop is true (project logs route)
- Uses existing `/logs` API endpoint with `from`/`to` parameters
- No server-side state required; entire view is reproducible from URL
- If target log not visible in first page, use pagination to locate

## Usage

### Basic Example

```tsx
import {EmbeddedLogs} from '@/components/logs/EmbeddedLogs'

function MyComponent() {
  return (
    <EmbeddedLogs
      projectId={123}
      centerTimestamp="2024-01-15T10:30:00Z"
      contextMinutes={5}
    />
  )
}
```

### Issue Detail Page Example

Show logs surrounding an error event:

```tsx
<EmbeddedLogs
  projectId={issue.projectId}
  centerTimestamp={latestEvent.timestamp}
  contextMinutes={5}
  environment={latestEvent.environment}
  service={latestEventTags.service}
  maxHeight="500px"
  showHeader={false}
  className="border-0 rounded-none"
/>
```

### Advanced Filtering

```tsx
<EmbeddedLogs
  projectId={projectId}
  centerTimestamp={errorTimestamp}
  contextMinutes={10}
  query="error OR exception"
  levels={['error', 'warn']}
  service="api-server"
  environment="production"
  tags={{
    version: "1.2.3",
    region: "us-east-1"
  }}
  maxHeight="600px"
/>
```

## Props

| Prop | Type | Default | Description |
|------|------|---------|-------------|
| `projectId` | `number` | required | The project ID to fetch logs from |
| `centerTimestamp` | `string` | - | ISO timestamp to center the time window around |
| `contextMinutes` | `number` | `5` | Minutes before/after centerTimestamp to show |
| `query` | `string` | - | Text search query |
| `levels` | `string[]` | - | Filter by log levels (e.g., `['error', 'warn']`) |
| `service` | `string` | - | Filter by service name |
| `environment` | `string` | - | Filter by environment |
| `tags` | `Record<string, string>` | - | Additional tag filters |
| `maxHeight` | `string` | `'400px'` | Maximum height of the logs container |
| `showHeader` | `boolean` | `true` | Show/hide the header bar |
| `compact` | `boolean` | `false` | Use compact styling (smaller text/padding) |
| `className` | `string` | - | Additional CSS classes |

## Integration Points

The component is currently integrated in:

- **Issue Detail Page** (`/issues/$issueId`): Shows logs around the error timestamp with context from the issue's environment and service

## Implementation Details

- Uses React Query for data fetching and caching
- Automatically calculates time range based on `centerTimestamp` and `contextMinutes`
- Fetches up to 100 logs per page
- Maintains cursor-based pagination state
- Opens log details in a reusable `LogDetail` slide-over panel
