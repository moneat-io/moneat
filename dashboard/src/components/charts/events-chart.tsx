import {Card, CardContent, CardHeader, CardTitle} from '@/components/ui/card'
import {Area, AreaChart, CartesianGrid, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis,} from 'recharts'
import type {TimelinePoint} from '@/lib/api'

interface ReleaseMarker {
  version: string
  timestamp: string
}

interface EventsChartProps {
  data: TimelinePoint[]
  title?: string
  height?: number
  releaseMarkers?: ReleaseMarker[]
}

const formatTime = (timestamp: string) =>
  new Date(timestamp).toLocaleString('en-US', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
  })

export function EventsChart({
  data,
  title = 'Events Over Time',
  height = 300,
  releaseMarkers = [],
}: EventsChartProps) {
  const chartData = data.map((point) => ({
    timestamp: new Date(point.timestamp).getTime(),
    time: formatTime(point.timestamp),
    count: point.count,
  }))

  const releaseLines = releaseMarkers
    .map((m) => ({
      ...m,
      timestamp: new Date(m.timestamp).getTime(),
    }))
    .filter((m) => {
      if (chartData.length === 0) return false
      const min = chartData[0]!.timestamp
      const max = chartData[chartData.length - 1]!.timestamp
      return m.timestamp >= min && m.timestamp <= max
    })

  return (
    <Card className="border-t-4 border-t-blue-500/50">
      <CardHeader>
        <CardTitle>{title}</CardTitle>
      </CardHeader>
      <CardContent>
        <ResponsiveContainer width="100%" height={height}>
          <AreaChart data={chartData}>
            <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
            <XAxis
              dataKey="timestamp"
              type="number"
              domain={['dataMin', 'dataMax']}
              tickFormatter={(ts) => formatTime(new Date(ts).toISOString())}
              fontSize={12}
              tickLine={false}
              axisLine={false}
              className="fill-muted-foreground"
            />
            <YAxis
              fontSize={12}
              tickLine={false}
              axisLine={false}
              className="fill-muted-foreground"
            />
            <Tooltip
              contentStyle={{
                backgroundColor: 'hsl(var(--popover) / 0.95)',
                border: '1px solid hsl(var(--border))',
                borderRadius: '6px',
                color: 'hsl(var(--popover-foreground))',
                padding: '8px 12px',
                fontSize: '13px',
              }}
              labelStyle={{
                color: 'hsl(var(--popover-foreground))',
                fontWeight: '500',
                fontSize: '13px',
              }}
              itemStyle={{
                color: 'hsl(var(--popover-foreground))',
                fontSize: '13px',
              }}
            />
            <Area
              type="monotone"
              dataKey="count"
              stroke="hsl(var(--primary))"
              fill="hsl(var(--primary))"
              fillOpacity={0.2}
            />
            {releaseLines.map((marker) => (
              <ReferenceLine
                key={marker.version}
                x={marker.timestamp}
                stroke="hsl(var(--muted-foreground))"
                strokeDasharray="4 4"
                label={{
                  value: marker.version,
                  position: 'top',
                  fill: 'hsl(var(--muted-foreground))',
                  fontSize: 11,
                }}
              />
            ))}
          </AreaChart>
        </ResponsiveContainer>
      </CardContent>
    </Card>
  )
}
