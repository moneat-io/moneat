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
import {
  ChevronLeft,
  ChevronRight,
  Download,
  ExternalLink,
  Package,
  Search,
  ShieldAlert,
  type LucideIcon,
} from 'lucide-react'
import {
  api,
  formatErrorForLogging,
  type VulnerabilityFindingResponse,
  type VulnerabilityInventoryItem,
} from '@/lib/api'
import {Badge, type BadgeProps} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {SectionCard} from '@/components/ui/section-card'
import {StatCard} from '@/components/ui/stat-card'
import {Input} from '@/components/ui/input'
import {Sheet, SheetContent, SheetHeader, SheetTitle} from '@/components/ui/sheet'
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from '@/components/ui/table'
import {useToast} from '@/hooks/useToast'
import {SecurityError} from '@/components/security/SecurityError'
import {STATUS_LABELS} from '@/components/security/securityChips'

export const Route = createFileRoute('/security/vulnerabilities')({
  component: VulnerabilitiesTab,
})

type ExportFormat = 'cyclonedx' | 'spdx'
type SummaryValue = number | 'Loading' | 'Unavailable'

// Map vulnerability severity / triage status onto the shared status language so
// these chips match the rest of the app rather than a private palette.
function severityBadgeVariant(severity?: string | null): BadgeProps['variant'] {
  switch ((severity ?? '').toLowerCase()) {
    case 'critical':
      return 'dangerSolid'
    case 'high':
      return 'danger'
    case 'medium':
      return 'warning'
    case 'low':
      return 'info'
    default:
      return 'neutral'
  }
}

function statusBadgeVariant(status?: string | null): BadgeProps['variant'] {
  switch ((status ?? '').toLowerCase()) {
    case 'open':
      return 'info'
    case 'under_review':
      return 'warning'
    default:
      return 'neutral'
  }
}

const VULNERABILITY_PAGE_SIZE = 50
const FINDING_BUTTON_CLASS_NAME = [
  'bg-transparent',
  'p-0',
  'text-left',
  'font-medium',
  'text-foreground',
  'hover:underline',
  'focus-visible:outline-none',
  'focus-visible:ring-2',
  'focus-visible:ring-ring',
].join(' ')

function VulnerabilitiesTab() {
  const {toast} = useToast()
  const [search, setSearch] = useState('')
  const [selected, setSelected] = useState<VulnerabilityFindingResponse | null>(null)
  const [exporting, setExporting] = useState<ExportFormat | null>(null)
  const [findingsPage, setFindingsPage] = useState(0)
  const [inventoryPage, setInventoryPage] = useState(0)

  const findingsParams = useMemo(
    () => ({
      search: search.trim() || undefined,
      limit: VULNERABILITY_PAGE_SIZE,
      offset: findingsPage * VULNERABILITY_PAGE_SIZE,
    }),
    [findingsPage, search]
  )
  const inventoryParams = useMemo(
    () => ({
      search: search.trim() || undefined,
      limit: VULNERABILITY_PAGE_SIZE,
      offset: inventoryPage * VULNERABILITY_PAGE_SIZE,
    }),
    [inventoryPage, search]
  )
  const summaryQuery = useQuery({
    queryKey: ['security-vulnerability-summary'],
    queryFn: () => api.getVulnerabilitySummary(),
  })
  const inventoryQuery = useQuery({
    queryKey: ['security-vulnerability-inventory', inventoryParams],
    queryFn: () => api.listVulnerabilityInventory(inventoryParams),
  })
  const findingsQuery = useQuery({
    queryKey: ['security-vulnerability-findings', findingsParams],
    queryFn: () => api.listVulnerabilityFindings(findingsParams),
  })

  const inventory = inventoryQuery.data?.inventory ?? []
  const findings = findingsQuery.data?.findings ?? []
  const inventoryTotal = inventoryQuery.data?.total_count ?? 0
  const findingsTotal = findingsQuery.data?.total_count ?? 0

  function handleSearchChange(value: string) {
    setSearch(value)
    setFindingsPage(0)
    setInventoryPage(0)
  }

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
      {summaryQuery.isError ? (
        <SecurityError title="Couldn’t load vulnerability summary" error={summaryQuery.error} />
      ) : (
        <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
          <MetricCard
            label="Packages"
            value={summaryMetricValue(summaryQuery.isLoading, summaryQuery.data?.package_count)}
            icon={Package}
            tone="info"
          />
          <MetricCard
            label="Findings"
            value={summaryMetricValue(summaryQuery.isLoading, summaryQuery.data?.finding_count)}
            icon={ShieldAlert}
            tone="accent"
          />
          <MetricCard
            label="Critical"
            value={summaryMetricValue(summaryQuery.isLoading, summaryQuery.data?.critical_count)}
            icon={ShieldAlert}
            tone="danger"
          />
          <MetricCard
            label="High"
            value={summaryMetricValue(summaryQuery.isLoading, summaryQuery.data?.high_count)}
            icon={ShieldAlert}
            tone="warning"
          />
        </div>
      )}

      <div className="flex flex-col gap-2 md:flex-row md:items-center">
        <div className="relative max-w-md flex-1">
          <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" />
          <Input
            value={search}
            onChange={(event) => handleSearchChange(event.target.value)}
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
        <SectionCard
          title="Findings"
          icon={ShieldAlert}
          iconTone="danger"
          count={findingsQuery.isLoading ? 'Loading' : findingsTotal}
          flushBody
        >
          {findingsQuery.isLoading ? (
            <LoadingTable columns={6} message="Loading vulnerability findings" />
          ) : (
            <FindingsTable findings={findings} onSelect={setSelected} />
          )}
          <PaginationControls
            label="Findings"
            page={findingsPage}
            pageSize={VULNERABILITY_PAGE_SIZE}
            totalCount={findingsTotal}
            isLoading={findingsQuery.isLoading || findingsQuery.isFetching}
            onPageChange={setFindingsPage}
          />
        </SectionCard>
      )}

      {inventoryQuery.isError ? (
        <SecurityError title="Couldn’t load package inventory" error={inventoryQuery.error} />
      ) : (
        <SectionCard
          title="Inventory"
          icon={Package}
          iconTone="info"
          count={inventoryQuery.isLoading ? 'Loading' : inventoryTotal}
          flushBody
        >
          {inventoryQuery.isLoading ? (
            <LoadingTable columns={5} message="Loading package inventory" />
          ) : (
            <InventoryTable inventory={inventory} />
          )}
          <PaginationControls
            label="Inventory"
            page={inventoryPage}
            pageSize={VULNERABILITY_PAGE_SIZE}
            totalCount={inventoryTotal}
            isLoading={inventoryQuery.isLoading || inventoryQuery.isFetching}
            onPageChange={setInventoryPage}
          />
        </SectionCard>
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

function summaryMetricValue(isLoading: boolean, value: number | undefined): SummaryValue {
  if (isLoading) return 'Loading'
  return value ?? 'Unavailable'
}

function MetricCard({
  label,
  value,
  icon: Icon,
  tone = 'accent',
}: {
  label: string
  value: SummaryValue
  icon?: LucideIcon
  tone?: 'info' | 'accent' | 'danger' | 'warning'
}) {
  return <StatCard label={label} value={value} icon={Icon} tone={tone} />
}

function LoadingTable({columns, message}: {columns: number; message: string}) {
  return (
    <Table>
      <TableBody>
        <TableRow>
          <TableCell colSpan={columns} className="py-6 text-center text-muted-foreground">
            {message}
          </TableCell>
        </TableRow>
      </TableBody>
    </Table>
  )
}

function PaginationControls({
  label,
  page,
  pageSize,
  totalCount,
  isLoading,
  onPageChange,
}: {
  label: string
  page: number
  pageSize: number
  totalCount: number
  isLoading: boolean
  onPageChange: (page: number) => void
}) {
  if (totalCount <= pageSize && page === 0) return null

  const totalPages = Math.max(1, Math.ceil(totalCount / pageSize))
  const canGoPrevious = page > 0 && !isLoading
  const canGoNext = page + 1 < totalPages && !isLoading

  return (
    <div className="flex items-center justify-between border-t px-3 py-2 text-xs text-muted-foreground">
      <span>{pageRange(page, pageSize, totalCount)}</span>
      <div className="flex gap-1">
        <Button
          type="button"
          variant="outline"
          size="icon"
          className="h-7 w-7"
          aria-label={`${label} previous page`}
          onClick={() => onPageChange(Math.max(0, page - 1))}
          disabled={!canGoPrevious}
        >
          <ChevronLeft className="h-3.5 w-3.5" />
        </Button>
        <Button
          type="button"
          variant="outline"
          size="icon"
          className="h-7 w-7"
          aria-label={`${label} next page`}
          onClick={() => onPageChange(Math.min(totalPages - 1, page + 1))}
          disabled={!canGoNext}
        >
          <ChevronRight className="h-3.5 w-3.5" />
        </Button>
      </div>
    </div>
  )
}

function pageRange(page: number, pageSize: number, totalCount: number): string {
  if (totalCount === 0) return '0 of 0'
  const start = page * pageSize + 1
  const end = Math.min(totalCount, start + pageSize - 1)
  return `${start}-${end} of ${totalCount}`
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
            <TableCell className="pl-3">
              <button
                type="button"
                className={FINDING_BUTTON_CLASS_NAME}
                aria-label={`Open vulnerability finding ${finding.cve_id ?? finding.advisory_id}`}
                onClick={(event) => {
                  event.stopPropagation()
                  onSelect(finding)
                }}
              >
                {finding.cve_id ?? finding.advisory_id}
              </button>
            </TableCell>
            <TableCell>
              {finding.package_name}
              <span className="ml-1 text-muted-foreground">{finding.package_version}</span>
            </TableCell>
            <TableCell>{finding.target_name || 'Unscoped'}</TableCell>
            <TableCell>
              <Badge variant={severityBadgeVariant(finding.severity)} size="sm" className="capitalize">
                {finding.severity}
              </Badge>
            </TableCell>
            <TableCell>
              <Badge variant={statusBadgeVariant(finding.status)} size="sm">
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
  inventory: VulnerabilityInventoryItem[]
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
          <TableRow
            key={`${item.package_name}|${item.package_version}|${item.ecosystem}|${item.purl}|${item.target_name}`}
          >
            <TableCell className="pl-3 font-medium">{item.package_name}</TableCell>
            <TableCell className="font-mono text-xs">{item.package_version}</TableCell>
            <TableCell>
              <Badge variant="neutral" size="sm">{item.package_type || 'package'}</Badge>
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
