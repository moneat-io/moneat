import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import {
  BarChart as RechartsBarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
} from 'recharts'

interface BarChartProps {
  data: Record<string, number>
  title: string
  height?: number
  color?: string
  layout?: 'vertical' | 'horizontal'
}

export function BarChart({
  data,
  title,
  height = 300,
  color = 'hsl(var(--primary))',
  layout = 'horizontal',
}: BarChartProps) {
  const chartData = Object.entries(data)
    .map(([name, value]) => ({
      name,
      value,
    }))
    .sort((a, b) => b.value - a.value)

  return (
    <Card>
      <CardHeader>
        <CardTitle>{title}</CardTitle>
      </CardHeader>
      <CardContent>
        <ResponsiveContainer width="100%" height={height}>
          <RechartsBarChart
            data={chartData}
            layout={layout}
            margin={{ top: 5, right: 30, left: 20, bottom: 5 }}
          >
            <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
            {layout === 'horizontal' ? (
              <>
                <XAxis
                  dataKey="name"
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
              </>
            ) : (
              <>
                <XAxis
                  type="number"
                  fontSize={12}
                  tickLine={false}
                  axisLine={false}
                  className="fill-muted-foreground"
                />
                <YAxis
                  type="category"
                  dataKey="name"
                  fontSize={12}
                  tickLine={false}
                  axisLine={false}
                  className="fill-muted-foreground"
                  width={100}
                />
              </>
            )}
            <Tooltip
              contentStyle={{
                backgroundColor: 'hsl(var(--background))',
                border: '1px solid hsl(var(--border))',
                borderRadius: '6px',
              }}
            />
            <Bar dataKey="value" fill={color} radius={4} />
          </RechartsBarChart>
        </ResponsiveContainer>
      </CardContent>
    </Card>
  )
}
