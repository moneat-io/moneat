# Embedded Logs Component

The `EmbeddedLogs` component provides a reusable way to display logs with contextual filtering. It's designed to be embedded in other pages, such as the issue detail page.

## Features

- **Time-based context**: Show logs around a specific timestamp (e.g., ±5 minutes around an error)
- **Flexible filtering**: Filter by service, environment, log levels, tags, and search query
- **Compact or full mode**: Customize header visibility and height
- **Pagination**: Navigate through log results
- **Detail view**: Click any log to see full details in a slide-over panel

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
