import {Card, CardContent, CardHeader, CardTitle} from '@/components/ui/card'
import {Cell, Legend, Pie, PieChart, ResponsiveContainer, Tooltip} from 'recharts'

interface DonutChartProps {
  data: Record<string, number>
  title: string
  height?: number
  colors?: string[]
}

const DEFAULT_COLORS = [
  'hsl(var(--chart-1))',
  'hsl(var(--chart-2))',
  'hsl(var(--chart-3))',
  'hsl(var(--chart-4))',
  'hsl(var(--chart-5))',
]

export function DonutChart({ data, title, height = 300, colors = DEFAULT_COLORS }: DonutChartProps) {
  const chartData = Object.entries(data).map(([name, value]) => ({
    name,
    value,
  }))

  return (
    <Card>
      <CardHeader>
        <CardTitle>{title}</CardTitle>
      </CardHeader>
      <CardContent>
        <ResponsiveContainer width="100%" height={height}>
          <PieChart>
            <Pie
              data={chartData}
              cx="50%"
              cy="50%"
              innerRadius={60}
              outerRadius={100}
              fill="#8884d8"
              paddingAngle={5}
              dataKey="value"
              label={({ name, percent }) => `${name} (${(percent * 100).toFixed(0)}%)`}
            >
              {chartData.map((_, index) => (
                <Cell key={`cell-${index}`} fill={colors[index % colors.length]} />
              ))}
            </Pie>
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
            <Legend />
          </PieChart>
        </ResponsiveContainer>
      </CardContent>
    </Card>
  )
}
