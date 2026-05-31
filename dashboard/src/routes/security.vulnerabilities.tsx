// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {useMemo, useState} from 'react'
import {createFileRoute} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {Download, ExternalLink, Package, Search, ShieldAlert, type LucideIcon} from 'lucide-react'
import {api, formatErrorForLogging, type VulnerabilityFindingResponse} from '@/lib/api'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Card, CardContent, CardHeader, CardTitle} from '@/components/ui/card'
import {Input} from '@/components/ui/input'
import {Sheet, SheetContent, SheetHeader, SheetTitle} from '@/components/ui/sheet'
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from '@/components/ui/table'
import {useToast} from '@/hooks/useToast'
import {SecurityError} from '@/components/security/SecurityError'
import {colorFor, severityColors, statusColors, STATUS_LABELS} from '@/components/security/securityChips'

export const Route = createFileRoute('/security/vulnerabilities')({
  component: VulnerabilitiesTab,
})

type ExportFormat = 'cyclonedx' | 'spdx'

function VulnerabilitiesTab() {
  const {toast} = useToast()
  const [search, setSearch] = useState('')
  const [selected, setSelected] = useState<VulnerabilityFindingResponse | null>(null)
  const [exporting, setExporting] = useState<ExportFormat | null>(null)

  const listParams = useMemo(() => ({search: search || undefined, limit: 100}), [search])
  const summaryQuery = useQuery({
    queryKey: ['security-vulnerability-summary'],
    queryFn: () => api.getVulnerabilitySummary(),
  })
  const inventoryQuery = useQuery({
    queryKey: ['security-vulnerability-inventory', listParams],
    queryFn: () => api.listVulnerabilityInventory(listParams),
  })
  const findingsQuery = useQuery({
    queryKey: ['security-vulnerability-findings', listParams],
    queryFn: () => api.listVulnerabilityFindings(listParams),
  })

  const inventory = inventoryQuery.data?.inventory ?? []
  const findings = findingsQuery.data?.findings ?? []

  async function exportSbom(format: ExportFormat) {
    setExporting(format)
    try {
      const body = await api.exportVulnerabilitySbom(format)
      downloadJson(format === 'spdx' ? 'moneat-sbom.spdx.json' : 'moneat-sbom.cdx.json', body)
    } catch (error) {
      toast({title: 'Export failed', description: formatErrorForLogging(error), variant: 'destructive'})
    } finally {
      setExporting(null)
    }
  }

  return (
    <div className="space-y-3">
      <div className="grid gap-2 md:grid-cols-4">
        <MetricCard label="Packages" value={summaryQuery.data?.package_count ?? 0} icon={Package} />
        <MetricCard label="Findings" value={summaryQuery.data?.finding_count ?? 0} icon={ShieldAlert} />
        <MetricCard label="Critical" value={summaryQuery.data?.critical_count ?? 0} tone="critical" />
        <MetricCard label="High" value={summaryQuery.data?.high_count ?? 0} tone="high" />
      </div>

      <div className="flex flex-col gap-2 md:flex-row md:items-center">
        <div className="relative max-w-md flex-1">
          <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" />
          <Input
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder="Search package, version, target"
            className="pl-9"
          />
        </div>
        <div className="flex gap-2">
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={() => void exportSbom('cyclonedx')}
            disabled={exporting != null}
          >
            <Download className="mr-1.5 h-3.5 w-3.5" />
            CycloneDX
          </Button>
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={() => void exportSbom('spdx')}
            disabled={exporting != null}
          >
            <Download className="mr-1.5 h-3.5 w-3.5" />
            SPDX
          </Button>
        </div>
      </div>

      {findingsQuery.isError ? (
        <SecurityError title="Couldn’t load vulnerability findings" error={findingsQuery.error} />
      ) : (
        <Card>
          <CardHeader className="px-2.5 py-1.5">
            <CardTitle className="text-xs">Findings ({findingsQuery.data?.total_count ?? findings.length})</CardTitle>
          </CardHeader>
          <CardContent className="p-0">
            <FindingsTable findings={findings} onSelect={setSelected} />
          </CardContent>
        </Card>
      )}

      {inventoryQuery.isError ? (
        <SecurityError title="Couldn’t load package inventory" error={inventoryQuery.error} />
      ) : (
        <Card>
          <CardHeader className="px-2.5 py-1.5">
            <CardTitle className="text-xs">
              Inventory ({inventoryQuery.data?.total_count ?? inventory.length})
            </CardTitle>
          </CardHeader>
          <CardContent className="p-0">
            <InventoryTable inventory={inventory} />
          </CardContent>
        </Card>
      )}

      <Sheet open={selected != null} onOpenChange={(open) => !open && setSelected(null)}>
        <SheetContent className="w-full overflow-y-auto sm:max-w-md">
          <SheetHeader>
            <SheetTitle className="text-sm">{selected?.cve_id ?? selected?.advisory_id}</SheetTitle>
          </SheetHeader>
          {selected && <FindingDetail finding={selected} />}
        </SheetContent>
      </Sheet>
    </div>
  )
}

function MetricCard({
  label,
  value,
  icon: Icon,
  tone,
}: {
  label: string
  value: number
  icon?: LucideIcon
  tone?: 'critical' | 'high'
}) {
  const toneClass = tone ? colorFor(severityColors, tone) : ''
  return (
    <Card>
      <CardContent className="flex items-center justify-between px-3 py-2">
        <div>
          <p className="text-xs text-muted-foreground">{label}</p>
          <p className="text-lg font-semibold tabular-nums">{value}</p>
        </div>
        {Icon ? <Icon className="h-4 w-4 text-muted-foreground" /> : <Badge className={toneClass}>{label}</Badge>}
      </CardContent>
    </Card>
  )
}

function FindingsTable({
  findings,
  onSelect,
}: {
  findings: VulnerabilityFindingResponse[]
  onSelect: (finding: VulnerabilityFindingResponse) => void
}) {
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead className="pl-3">Advisory</TableHead>
          <TableHead>Package</TableHead>
          <TableHead>Target</TableHead>
          <TableHead>Severity</TableHead>
          <TableHead>Status</TableHead>
          <TableHead className="pr-3 text-right">CVSS</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {findings.map((finding) => (
          <TableRow
            key={finding.signal_id}
            className="cursor-pointer"
            onClick={() => onSelect(finding)}
          >
            <TableCell className="pl-3 font-medium">{finding.cve_id ?? finding.advisory_id}</TableCell>
            <TableCell>
              {finding.package_name}
              <span className="ml-1 text-muted-foreground">{finding.package_version}</span>
            </TableCell>
            <TableCell>{finding.target_name || 'Unscoped'}</TableCell>
            <TableCell>
              <Badge variant="outline" className={colorFor(severityColors, finding.severity)}>
                {finding.severity}
              </Badge>
            </TableCell>
            <TableCell>
              <Badge variant="outline" className={colorFor(statusColors, finding.status)}>
                {STATUS_LABELS[finding.status]}
              </Badge>
            </TableCell>
            <TableCell className="pr-3 text-right tabular-nums">{finding.cvss_score ?? 'None'}</TableCell>
          </TableRow>
        ))}
        {findings.length === 0 && (
          <TableRow>
            <TableCell colSpan={6} className="py-6 text-center text-muted-foreground">
              No vulnerability findings
            </TableCell>
          </TableRow>
        )}
      </TableBody>
    </Table>
  )
}

function InventoryTable({
  inventory,
}: {
  inventory: Array<{
    package_name: string
    package_version: string
    package_type: string
    target_name: string
    host: string
    image_name: string
    last_seen: string
  }>
}) {
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead className="pl-3">Package</TableHead>
          <TableHead>Version</TableHead>
          <TableHead>Type</TableHead>
          <TableHead>Target</TableHead>
          <TableHead className="pr-3">Last seen</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {inventory.map((item) => (
          <TableRow key={`${item.package_name}|${item.package_version}|${item.target_name}`}>
            <TableCell className="pl-3 font-medium">{item.package_name}</TableCell>
            <TableCell className="font-mono text-xs">{item.package_version}</TableCell>
            <TableCell>
              <Badge variant="outline">{item.package_type || 'package'}</Badge>
            </TableCell>
            <TableCell>{item.target_name || item.host || item.image_name || 'Unscoped'}</TableCell>
            <TableCell className="pr-3 text-muted-foreground">{item.last_seen}</TableCell>
          </TableRow>
        ))}
        {inventory.length === 0 && (
          <TableRow>
            <TableCell colSpan={5} className="py-6 text-center text-muted-foreground">
              No packages
            </TableCell>
          </TableRow>
        )}
      </TableBody>
    </Table>
  )
}

function FindingDetail({finding}: {finding: VulnerabilityFindingResponse}) {
  return (
    <div className="mt-4 space-y-3 text-sm">
      <DetailRow label="Package" value={`${finding.package_name} ${finding.package_version}`} />
      <DetailRow label="Severity" value={finding.severity} />
      <DetailRow label="CVSS" value={finding.cvss_score?.toString() ?? 'None'} />
      <DetailRow label="Fixed in" value={finding.fixed_version ?? 'None'} />
      <DetailRow label="Target" value={finding.target_name || 'Unscoped'} />
      <a
        href={finding.link}
        target="_blank"
        rel="noreferrer"
        className="inline-flex items-center gap-1 text-primary hover:underline"
      >
        Advisory
        <ExternalLink className="h-3.5 w-3.5" />
      </a>
    </div>
  )
}

function DetailRow({label, value}: {label: string; value: string}) {
  return (
    <div className="flex items-center justify-between gap-3 border-b pb-2">
      <span className="text-muted-foreground">{label}</span>
      <span className="text-right font-medium">{value}</span>
    </div>
  )
}

function downloadJson(filename: string, body: string) {
  const blob = new Blob([body], {type: 'application/json'})
  const url = globalThis.URL.createObjectURL(blob)
  const link = globalThis.document.createElement('a')
  link.href = url
  link.download = filename
  link.click()
  link.remove()
  globalThis.URL.revokeObjectURL(url)
}
