// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

// Render the flamegraph model to a standalone SVG (and PNG) for sharing in bug
// reports, docs, and PRs. Dependency-free: the SVG is generated from the same
// flattened model the screen renderer uses; the PNG is rasterised in-browser.

import {shortName} from './frameModel'

export interface ExportFrame {
  name: string
  depth: number
  /** Left offset as a percentage (0–100). */
  x: number
  /** Width as a percentage (0–100). */
  width: number
  color: string
}

export interface ExportOptions {
  width: number
  rowHeight: number
  title?: string
}

const BG = '#0b0f17'
const HEADER = 28

function escapeXml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

export function framesToSvg(frames: ExportFrame[], opts: ExportOptions): string {
  const {width, rowHeight} = opts
  const maxDepth = frames.reduce((m, f) => Math.max(m, f.depth), 0)
  const height = HEADER + (maxDepth + 1) * rowHeight + 4

  const parts: string[] = []
  parts.push(
    `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" ` +
      `viewBox="0 0 ${width} ${height}" font-family="ui-monospace, monospace">`,
  )
  parts.push(`<rect width="${width}" height="${height}" fill="${BG}"/>`)
  if (opts.title) {
    parts.push(
      `<text x="8" y="18" fill="#cbd5e1" font-size="13" font-weight="600">${escapeXml(
        opts.title,
      )}</text>`,
    )
  }

  for (const f of frames) {
    const x = (f.x / 100) * width
    const w = Math.max((f.width / 100) * width - 1, 0.5)
    const y = HEADER + f.depth * rowHeight
    const h = rowHeight - 1
    parts.push(`<rect x="${x.toFixed(2)}" y="${y}" width="${w.toFixed(2)}" height="${h}" rx="2" fill="${f.color}"/>`)
    const label = shortName(f.name)
    const maxChars = Math.floor(w / 6.2)
    if (maxChars >= 3 && label.length > 0) {
      const text = label.length > maxChars ? `${label.slice(0, maxChars - 1)}…` : label
      parts.push(
        `<text x="${(x + 3).toFixed(2)}" y="${y + h - 6}" fill="#ffffff" font-size="11">${escapeXml(
          text,
        )}</text>`,
      )
    }
  }

  parts.push('</svg>')
  return parts.join('')
}

export function svgDimensions(frames: ExportFrame[], opts: ExportOptions): {
  width: number
  height: number
} {
  const maxDepth = frames.reduce((m, f) => Math.max(m, f.depth), 0)
  return {width: opts.width, height: HEADER + (maxDepth + 1) * opts.rowHeight + 4}
}

export function triggerDownload(href: string, filename: string) {
  const a = document.createElement('a')
  a.href = href
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
}

export function downloadSvg(svg: string, filename: string) {
  const blob = new Blob([svg], {type: 'image/svg+xml;charset=utf-8'})
  const url = URL.createObjectURL(blob)
  triggerDownload(url, filename)
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

export async function downloadPng(
  svg: string,
  width: number,
  height: number,
  filename: string,
): Promise<void> {
  const scale = window.devicePixelRatio || 2
  const blob = new Blob([svg], {type: 'image/svg+xml;charset=utf-8'})
  const url = URL.createObjectURL(blob)
  try {
    const img = new Image()
    await new Promise<void>((resolve, reject) => {
      img.onload = () => resolve()
      img.onerror = () => reject(new Error('Failed to render flamegraph image'))
      img.src = url
    })
    const canvas = document.createElement('canvas')
    canvas.width = width * scale
    canvas.height = height * scale
    const ctx = canvas.getContext('2d')
    if (!ctx) throw new Error('Canvas not available')
    ctx.scale(scale, scale)
    ctx.drawImage(img, 0, 0, width, height)
    const dataUrl = canvas.toDataURL('image/png')
    triggerDownload(dataUrl, filename)
  } finally {
    URL.revokeObjectURL(url)
  }
}
